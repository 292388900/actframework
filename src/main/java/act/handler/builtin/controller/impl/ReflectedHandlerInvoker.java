package act.handler.builtin.controller.impl;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.Act;
import act.Trace;
import act.annotations.LargeResponse;
import act.annotations.SmallResponse;
import act.app.ActionContext;
import act.app.App;
import act.app.AppClassLoader;
import act.conf.AppConfig;
import act.controller.CacheSupportMetaInfo;
import act.controller.Controller;
import act.controller.ExpressController;
import act.controller.annotation.HandleCsrfFailure;
import act.controller.annotation.HandleMissingAuthentication;
import act.controller.annotation.Throttled;
import act.controller.builtin.ThrottleFilter;
import act.controller.captcha.RequireCaptcha;
import act.controller.meta.*;
import act.data.annotation.DateFormatPattern;
import act.data.annotation.Pattern;
import act.db.RequireDataBind;
import act.handler.NonBlock;
import act.handler.PreventDoubleSubmission;
import act.handler.SkipBuiltInEvents;
import act.handler.builtin.controller.*;
import act.handler.event.ReflectedHandlerInvokerInit;
import act.handler.event.ReflectedHandlerInvokerInvoke;
import act.inject.DependencyInjector;
import act.inject.param.JsonDto;
import act.inject.param.JsonDtoClassManager;
import act.inject.param.ParamValueLoaderManager;
import act.inject.param.ParamValueLoaderService;
import act.job.JobManager;
import act.job.TrackableWorker;
import act.plugin.ControllerPlugin;
import act.security.CORS;
import act.security.CSP;
import act.security.CSRF;
import act.sys.Env;
import act.util.*;
import act.view.*;
import act.ws.WebSocketConnectionManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.esotericsoftware.reflectasm.MethodAccess;
import org.osgl.$;
import org.osgl.exception.NotAppliedException;
import org.osgl.http.H;
import org.osgl.inject.BeanSpec;
import org.osgl.mvc.annotation.ResponseContentType;
import org.osgl.mvc.annotation.ResponseStatus;
import org.osgl.mvc.annotation.SessionFree;
import org.osgl.mvc.result.BadRequest;
import org.osgl.mvc.result.Conflict;
import org.osgl.mvc.result.RenderJSON;
import org.osgl.mvc.result.Result;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.enterprise.context.ApplicationScoped;

/**
 * Implement handler using
 * https://github.com/EsotericSoftware/reflectasm
 */
@ApplicationScoped
public class ReflectedHandlerInvoker<M extends HandlerMethodMetaInfo> extends DestroyableBase
        implements ActionHandlerInvoker, AfterInterceptorInvoker, ExceptionInterceptorInvoker {

    private static final Object[] DUMP_PARAMS = new Object[0];
    private App app;
    private ClassLoader cl;
    private ControllerClassMetaInfo controller;
    private Class<?> controllerClass;
    private MethodAccess methodAccess;
    private M handler;
    private int handlerIndex;
    private ConcurrentMap<H.Format, Boolean> templateAvailabilityCache = new ConcurrentHashMap<>();
    private $.Visitor<H.Format> templateChangeListener = new $.Visitor<H.Format>() {
        @Override
        public void visit(H.Format format) throws $.Break {
            templateAvailabilityCache.remove(format);
        }
    };
    protected Method method; //
    private ParamValueLoaderService paramLoaderService;
    private JsonDtoClassManager jsonDTOClassManager;
    private int paramCount;
    private int fieldsAndParamsCount;
    private String singleJsonFieldName;
    private boolean sessionFree;
    private boolean express;
    private boolean skipEvents;
    private List<BeanSpec> paramSpecs;
    private CORS.Spec corsSpec;
    private CSRF.Spec csrfSpec;
    private String csp;
    private boolean disableCsp;
    private boolean isStatic;
    private boolean requireCaptcha;
    private Object singleton;
    private H.Format forceResponseContentType;
    private H.Status forceResponseStatus;
    // Env doesn't match
    private boolean disabled;
    private String dspToken;
    private CacheSupportMetaInfo cacheSupport;
    // (field name: output name)
    private Map<Field, String> outputFields;
    // (param index: output name)
    private Map<Integer, String> outputParams;
    private boolean hasOutputVar;
    private String dateFormatPattern;
    private boolean noTemplateCache;
    private MissingAuthenticationHandler missingAuthenticationHandler;
    private MissingAuthenticationHandler csrfFailureHandler;
    private ThrottleFilter throttleFilter;
    private boolean async;
    private boolean byPassImplicityTemplateVariable;
    private boolean forceDataBinding;
    private boolean traceHandler;
    private boolean isLargeResponse;
    private boolean forceSmallResponse;
    private Class<? extends SerializeFilter> filters[];
    private SerializerFeature features[];
    private $.Function<ActionContext, Result> pluginBeforeHandler;
    private $.Func2<Result, ActionContext, Result> pluginAfterHandler;
    private Map<String, Object> attributes = new HashMap<>();
    private boolean enableCircularReferenceDetect;
    // it shall do full JSON string check when Accept is JSON and return type is String
    // however if the first full check is good, then the following check shall rely on quick check
    private boolean returnString;
    private boolean fullJsonStringChecked;
    private boolean fullJsonStringCheckFailure;

    private ReflectedHandlerInvoker(M handlerMetaInfo, App app) {
        this.app = app;
        this.cl = app.classLoader();
        this.handler = handlerMetaInfo;
        this.controller = handlerMetaInfo.classInfo();
        this.controllerClass = $.classForName(controller.className(), cl);
        this.disabled = !Env.matches(controllerClass);
        this.traceHandler = app.config().traceHandler();
        this.paramLoaderService = app.service(ParamValueLoaderManager.class).get(ActionContext.class);
        this.jsonDTOClassManager = app.service(JsonDtoClassManager.class);

        Class[] paramTypes = paramTypes(cl);
        try {
            method = controllerClass.getMethod(handlerMetaInfo.name(), paramTypes);
        } catch (NoSuchMethodException e) {
            throw E.unexpected(e);
        }
        this.returnString = method.getReturnType() == String.class;
        this.pluginBeforeHandler = ControllerPlugin.Manager.INST.beforeHandler(controllerClass, method);
        this.pluginAfterHandler = ControllerPlugin.Manager.INST.afterHandler(controllerClass, method);
        this.disabled = this.disabled || !Env.matches(method);
        this.forceDataBinding = method.isAnnotationPresent(RequireDataBind.class);
        this.async = null != method.getAnnotation(Async.class);
        if (this.async && (handlerMetaInfo.hasReturnOrThrowResult())) {
            logger.warn("handler return result will be ignored for async method: " + method);
        }
        this.isStatic = handlerMetaInfo.isStatic();
        if (!this.isStatic) {
            //constructorAccess = ConstructorAccess.get(controllerClass);
            methodAccess = MethodAccess.get(controllerClass);
            handlerIndex = methodAccess.getIndex(handlerMetaInfo.name(), paramTypes);
        } else {
            method.setAccessible(true);
        }

        if (method.isAnnotationPresent(RequireCaptcha.class)) {
            this.requireCaptcha = true;
        }

        Throttled throttleControl = method.getAnnotation(Throttled.class);
        if (null != throttleControl) {
            int throttle = throttleControl.value();
            if (throttle < 1) {
                throttle = app.config().requestThrottle();
            }
            Throttled.ExpireScale expireScale = throttleControl.expireScale();
            throttleFilter = new ThrottleFilter(throttle, expireScale.enabled());
        }

        this.isLargeResponse = method.getAnnotation(LargeResponse.class) != null;
        this.forceSmallResponse = method.getAnnotation(SmallResponse.class) != null;
        if (isLargeResponse && forceSmallResponse) {
            warn("found both @LargeResponse and @SmallResponse, will ignore @SmallResponse");
            forceSmallResponse = false;
        }

        FastJsonFilter filterAnno = method.getAnnotation(FastJsonFilter.class);
        if (null != filterAnno) {
            filters = filterAnno.value();
        }
        FastJsonFeature featureAnno = method.getAnnotation(FastJsonFeature.class);
        if (null != featureAnno) {
            features = featureAnno.value();
        }
        enableCircularReferenceDetect = hasAnnotation(EnableCircularReferenceDetect.class);

        DateFormatPattern pattern = method.getAnnotation(DateFormatPattern.class);
        if (null != pattern) {
            this.dateFormatPattern = pattern.value();
        } else {
            Pattern patternLegacy = method.getAnnotation(Pattern.class);
            if (null != patternLegacy) {
                this.dateFormatPattern = patternLegacy.value();
            }
        }
        if (null != this.dateFormatPattern) {
            handlerMetaInfo.dateFormatPattern(this.dateFormatPattern);
        }

        if (controllerClass.isAnnotationPresent(ExpressController.class)) {
            sessionFree = true;
            express = true;
            skipEvents = true;
        } else {
            sessionFree = hasAnnotation(SessionFree.class);
            express = hasAnnotation(NonBlock.class);
            skipEvents = hasAnnotation(SkipBuiltInEvents.class);
        }
        noTemplateCache = method.isAnnotationPresent(Template.NoCache.class);

        paramCount = handler.paramCount();
        paramSpecs = jsonDTOClassManager.beanSpecs(controllerClass, method);
        fieldsAndParamsCount = paramSpecs.size();
        if (fieldsAndParamsCount == 1) {
            singleJsonFieldName = paramSpecs.get(0).name();
        }

        CORS.Spec corsSpec = CORS.spec(method).chain(CORS.spec(controllerClass));
        this.corsSpec = corsSpec;

        CSRF.Spec csrfSpec = CSRF.spec(method).chain(CSRF.spec(controllerClass));
        this.csrfSpec = csrfSpec;

        CSP.Disable cspDisableAnno = getAnnotation(CSP.Disable.class);
        if (null != cspDisableAnno) {
            this.disableCsp = true;
        }

        if (!this.disableCsp) {
            CSP cspAnno = getAnnotation(CSP.class);
            if (null != cspAnno) {
                this.csp = cspAnno.value();
            }
        }

        if (!isStatic) {
            this.singleton = ReflectedInvokerHelper.tryGetSingleton(controllerClass, app);
        }

        if (null != controllerClass.getAnnotation(JsonView.class)) {
            forceResponseContentType = H.MediaType.JSON.format();
        }
        if (null != controllerClass.getAnnotation(CsvView.class)) {
            forceResponseContentType = H.MediaType.CSV.format();
        }
        // ResponseContentType takes priority of JsonView
        ResponseContentType contentType = controllerClass.getAnnotation(ResponseContentType.class);
        if (null != contentType) {
            forceResponseContentType = contentType.value().format();
        }

        // method annotation takes priority of class annotation
        if (null != method.getAnnotation(JsonView.class)) {
            forceResponseContentType = H.MediaType.JSON.format();
        }
        if (null != method.getAnnotation(CsvView.class)) {
            forceResponseContentType = H.MediaType.CSV.format();
        }
        contentType = method.getAnnotation(ResponseContentType.class);
        if (null != contentType) {
            forceResponseContentType = contentType.value().format();
        }

        ResponseStatus status = method.getAnnotation(ResponseStatus.class);
        if (null != status) {
            forceResponseStatus = H.Status.of(status.value());
        }

        PreventDoubleSubmission dsp = method.getAnnotation(PreventDoubleSubmission.class);
        if (null != dsp) {
            dspToken = dsp.value();
            if (PreventDoubleSubmission.DEFAULT.equals(dspToken)) {
                dspToken = app.config().dspToken();
            }
        }

        byPassImplicityTemplateVariable = (controllerClass.isAnnotationPresent(NoImplicitTemplateVariable.class) ||
                method.isAnnotationPresent(NoImplicitTemplateVariable.class));

        initOutputVariables();
        initCacheParams(app.config());
        initMissingAuthenticationAndCsrfCheckHandler();
        app.eventBus().emit(new ReflectedHandlerInvokerInit(this));
    }

    @Override
    protected void releaseResources() {
        app = null;
        cl = null;
        controller = null;
        controllerClass = null;
        method = null;
        methodAccess = null;
        handler.destroy();
        handler = null;
        cacheSupport = null;
        super.releaseResources();
    }

    @Override
    public int priority() {
        return handler.priority();
    }

    @Override
    public Method invokeMethod() {
        return method;
    }

    public interface ReflectedHandlerInvokerVisitor extends Visitor, $.Func2<Class<?>, Method, Void> {
    }

    public ReflectedHandlerInvoker attribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public <T> T attribute(String key) {
        return $.cast(attributes.get(key));
    }

    @Override
    public void accept(Visitor visitor) {
        ReflectedHandlerInvokerVisitor rv = (ReflectedHandlerInvokerVisitor) visitor;
        rv.apply(controllerClass, method);
    }

    public Class<?> controllerClass() {
        return controllerClass;
    }

    public Method method() {
        return method;
    }

    @Override
    public CacheSupportMetaInfo cacheSupport() {
        return cacheSupport;
    }

    @Override
    public MissingAuthenticationHandler missingAuthenticationHandler() {
        return missingAuthenticationHandler;
    }

    @Override
    public MissingAuthenticationHandler csrfFailureHandler() {
        return csrfFailureHandler;
    }

    public Result handle(final ActionContext context) {
        if (disabled) {
            return ActNotFound.get();
        }

        if (null != throttleFilter) {
            Result throttleResult = throttleFilter.handle(context);
            if (null != throttleResult) {
                return throttleResult;
            }
        }

        Result result = pluginBeforeHandler.apply(context);
        if (null != result) {
            return result;
        }

        if (requireCaptcha) {
            context.markAsRequireCaptcha();
        }

        if (enableCircularReferenceDetect) {
            context.enableCircularReferenceDetect();
        }

        context.setReflectedHandlerInvoker(this);
        app.eventBus().emit(new ReflectedHandlerInvokerInvoke(this, context));

        if (fieldsAndParamsCount == 1) {
            context.allowIgnoreParamNamespace();
        } else {
            context.disallowIgnoreParamNamespace();
        }

        if (isLargeResponse) {
            context.setLargeResponse();
        }

        if (null != filters) {
            context.fastjsonFilters(filters);
        }

        if (null != features) {
            context.fastjsonFeatures(features);
        }

        if (null != dateFormatPattern) {
            context.dateFormatPattern(dateFormatPattern);
        }

        if (byPassImplicityTemplateVariable && context.state().isHandling()) {
            context.byPassImplicitVariable();
        }

        context.templateChangeListener(templateChangeListener);
        if (noTemplateCache) {
            context.disableTemplateCaching();
        }

        context.currentMethod(method);

        String urlContext = this.controller.urlContext();
        if (S.notBlank(urlContext)) {
            context.urlContext(urlContext);
        }

        String templateContext = this.controller.templateContext();
        if (null != templateContext) {
            context.templateContext(templateContext);
        }
        preventDoubleSubmission(context);
        processForceResponse(context);
        if (forceDataBinding || context.state().isHandling()) {
            ensureJsonDtoGenerated(context);
        }
        final Object controller = controllerInstance(context);

        /*
         * We will send back response immediately when param validation
         * failed in either of the following cases:
         * 1) this is an ajax call
         * 2) the accept content type is **NOT** html
         */
        boolean failOnViolation = context.isAjax() || context.accept() != H.Format.HTML;

        final Object[] params = params(controller, context);

        context.ensureCaptcha();

        if (failOnViolation && context.hasViolation()) {
            String msg = context.violationMessage(";");
            return ActBadRequest.create(msg);
        }

        if (async) {
            JobManager jobManager = context.app().jobManager();
            String jobId = jobManager.prepare(new TrackableWorker() {
                @Override
                protected void run(ProgressGauge progressGauge) {
                    try {
                        invoke(handler, context, controller, params);
                    } catch (Exception e) {
                        logger.warn(e, "Error executing async handler: " + handler);
                    }
                }
            });
            context.setJobId(jobId);
            WebSocketConnectionManager wscm = app.getInstance(WebSocketConnectionManager.class);
            wscm.subscribe(context.session(), SimpleProgressGauge.wsJobProgressTag(jobId));
            jobManager.now(jobId);
            return new RenderJSON(C.Map("jobId", jobId));
        }

        try {
            return pluginAfterHandler.apply(invoke(handler, context, controller, params), context);
        } finally {
            if (hasOutputVar) {
                fillOutputVariables(controller, params, context);
            }
        }
    }

    @Override
    public Result handle(Result result, ActionContext actionContext) throws Exception {
        actionContext.setResult(result);
        return handle(actionContext);
    }

    @Override
    public Result handle(Exception e, ActionContext actionContext) throws Exception {
        actionContext.attribute(ActionContext.ATTR_EXCEPTION, e);
        return handle(actionContext);
    }

    @Override
    public boolean sessionFree() {
        return sessionFree;
    }

    @Override
    public boolean express() {
        return express;
    }

    @Override
    public boolean skipEvents() {
        return skipEvents;
    }

    public CORS.Spec corsSpec() {
        return corsSpec;
    }

    @Override
    public CSRF.Spec csrfSpec() {
        return csrfSpec;
    }

    @Override
    public String contentSecurityPolicy() {
        return disableCsp ? null : csp;
    }

    public boolean disableContentSecurityPolicy() {
        return disableCsp;
    }

    public void setLargeResponseHint() {
        if (!this.forceSmallResponse) {
            this.isLargeResponse = true;
        }
    }

    private void cacheJsonDto(ActContext<?> context, JsonDto dto) {
        context.attribute(JsonDto.CTX_ATTR_KEY, dto);
    }

    private void ensureJsonDtoGenerated(ActionContext context) {
        if (0 == fieldsAndParamsCount || !context.jsonEncoded()) {
            return;
        }
        Class<? extends JsonDto> dtoClass = jsonDTOClassManager.get(controllerClass, method);
        if (null == dtoClass) {
            // there are neither fields nor params
            return;
        }
        try {
            JsonDto dto = JSON.parseObject(patchedJsonBody(context), dtoClass);
            cacheJsonDto(context, dto);
        } catch (JSONException e) {
            if (e.getCause() != null) {
                warn(e.getCause(), "error parsing JSON data");
            } else {
                warn(e, "error parsing JSON data");
            }
            throw new BadRequest(e.getCause());
        }
    }

    private int fieldsAndParamsCount(ActionContext context) {
        if (fieldsAndParamsCount < 2) {
            return fieldsAndParamsCount;
        }
        return fieldsAndParamsCount - context.pathVarCount();
    }

    private String singleJsonFieldName(ActionContext context) {
        if (null != singleJsonFieldName) {
            return singleJsonFieldName;
        }
        for (BeanSpec spec: paramSpecs) {
            String name = spec.name();
            if (context.isPathVar(name)) {
                continue;
            }
            return name;
        }
        return null;
    }

    /**
     * Suppose method signature is: `public void foo(Foo foo)`, and a JSON content is
     * not `{"foo": {foo-content}}`, then wrap it as `{"foo": body}`
     */
    private String patchedJsonBody(ActionContext context) {
        String body = context.body();
        if (S.blank(body) || 1 < fieldsAndParamsCount(context)) {
            return body;
        }
        String theName = singleJsonFieldName(context);
        if (null == theName) {
            return body;
        }
        int theNameLen = theName.length();
        body = body.trim();
        boolean needPatch = body.charAt(0) == '[';
        if (!needPatch) {
            if (body.charAt(0) != '{') {
                throw new IllegalArgumentException("Cannot parse JSON string: " + body);
            }
            boolean startCheckName = false;
            int nameStart = -1;
            for (int i = 1; i < body.length(); ++i) {
                char c = body.charAt(i);
                if (c == ' ') {
                    continue;
                }
                if (startCheckName) {
                    if (c == '"') {
                        break;
                    }
                    int id = i - nameStart - 1;
                    if (id >= theNameLen || theName.charAt(i - nameStart - 1) != c) {
                        needPatch = true;
                        break;
                    }
                } else if (c == '"') {
                    startCheckName = true;
                    nameStart = i;
                }
            }
        }
        return needPatch ? S.fmt("{\"%s\": %s}", theName, body) : body;
    }

    private Class[] paramTypes(ClassLoader cl) {
        int sz = handler.paramCount();
        Class[] ca = new Class[sz];
        for (int i = 0; i < sz; ++i) {
            HandlerParamMetaInfo param = handler.param(i);
            ca[i] = $.classForName(param.type().getClassName(), cl);
        }
        return ca;
    }

    private void processForceResponse(ActionContext actionContext) {
        if (null != forceResponseContentType) {
            actionContext.accept(forceResponseContentType);
        }
        if (null != forceResponseStatus) {
            actionContext.forceResponseStatus(forceResponseStatus);
        }
    }

    private void preventDoubleSubmission(ActionContext context) {
        if (null == dspToken) {
            return;
        }
        H.Request req = context.req();
        if (req.method().safe()) {
            return;
        }
        String tokenValue = context.paramVal(dspToken);
        if (S.blank(tokenValue)) {
            return;
        }
        H.Session session = context.session();
        String cacheKey = S.concat("DSP-", dspToken);
        String cached = session.cached(cacheKey);
        if (S.eq(tokenValue, cached)) {
            throw Conflict.get();
        }
        session.cacheFor1Min(cacheKey, tokenValue);
    }

    private Object controllerInstance(ActionContext context) {
        if (isStatic) {
            return null;
        }
        if (null != singleton) {
            return singleton;
        }
        String controllerName = controllerClass.getName();
        Object inst = context.__controllerInstance(controllerName);
        if (null == inst) {
            inst = paramLoaderService.loadHostBean(controllerClass, context);
            context.__controllerInstance(controllerName, inst);
        }
        return inst;
    }

    private void initCacheParams(AppConfig config) {
        if (Act.isDev() && !config.cacheForOnDevMode()) {
            cacheSupport = CacheSupportMetaInfo.disabled();
            return;
        }
        CacheFor cacheFor = method.getAnnotation(CacheFor.class);
        cacheSupport = null == cacheFor ? CacheSupportMetaInfo.disabled() :  CacheSupportMetaInfo.enabled(
                new CacheKeyBuilder(cacheFor, S.concat(controllerClass.getName(), ".", method.getName())),
                cacheFor.value(),
                cacheFor.supportPost()
        );
    }

    private void fillOutputVariables(Object controller, Object[] params, ActionContext context) {
        if (!isStatic) {
            for (Map.Entry<Field, String> entry : outputFields.entrySet()) {
                Field field = entry.getKey();
                String outputName = entry.getValue();
                try {
                    Object val = field.get(controller);
                    context.renderArg(outputName, val);
                } catch (IllegalAccessException e) {
                    throw E.unexpected(e);
                }
            }
            context.fieldOutputVarCount(outputFields.size());
        }
        if (0 == params.length) {
            return;
        }
        for (Map.Entry<Integer, String> entry : outputParams.entrySet()) {
            int i = entry.getKey();
            String outputName = entry.getValue();
            context.renderArg(outputName, params[i]);
        }
    }

    private void initMissingAuthenticationAndCsrfCheckHandler() {
        HandleMissingAuthentication hma = getAnnotation(HandleMissingAuthentication.class);
        if (null != hma) {
            missingAuthenticationHandler = hma.value().handler(hma.custom());
        }

        HandleCsrfFailure hcf = getAnnotation(HandleCsrfFailure.class);
        if (null != hcf) {
            csrfFailureHandler = hcf.value().handler(hcf.custom());
        }
    }

    private void initOutputVariables() {
        Set<String> outputNames = new HashSet<>();
        outputFields = new HashMap<>();
        if (!isStatic) {
            List<Field> fields = $.fieldsOf(controllerClass);
            for (Field field : fields) {
                Output output = field.getAnnotation(Output.class);
                if (null != output) {
                    String fieldName = field.getName();
                    String outputName = output.value();
                    if (S.blank(outputName)) {
                        outputName = fieldName;
                    }
                    E.unexpectedIf(outputNames.contains(outputName), "output name already used: %s", outputName);
                    field.setAccessible(true);
                    outputFields.put(field, outputName);
                    outputNames.add(outputName);
                }
            }
        }

        try {
            outputParams = new HashMap<>();
            Class<?>[] paramTypes = method.getParameterTypes();
            int len = paramTypes.length;
            if (0 == len) {
                return;
            }
            Annotation outputRequestParams = method.getAnnotation(OutputRequestParams.class);
            if (null != outputRequestParams) {
                DependencyInjector injector = app.injector();
                for (int i = 0; i < len; ++i) {
                    if (!injector.isProvided(paramTypes[i])) {
                        String outputName = handler.param(i).name();
                        outputParams.put(i, outputName);
                        outputNames.add(outputName);
                    }
                }
            } else {
                Annotation[][] aaa = method.getParameterAnnotations();
                for (int i = 0; i < len; ++i) {
                    Annotation[] aa = aaa[i];
                    if (null == aa) {
                        continue;
                    }
                    Output output = null;
                    for (int j = aa.length - 1; j >= 0; --j) {
                        Annotation a = aa[j];
                        if (a.annotationType() == Output.class) {
                            output = $.cast(a);
                            break;
                        }
                    }
                    if (null == output) {
                        continue;
                    }
                    String outputName = output.value();
                    if (S.blank(outputName)) {
                        HandlerParamMetaInfo paramMetaInfo = handler.param(i);
                        outputName = paramMetaInfo.name();
                    }
                    E.unexpectedIf(outputNames.contains(outputName), "output name already used: %s", outputName);
                    outputParams.put(i, outputName);
                    outputNames.add(outputName);
                }
            }
        } finally {
            hasOutputVar = !outputNames.isEmpty();
        }
    }

    private Result invoke(M handlerMetaInfo, ActionContext context, Object controller, Object[] params) {
        Object result;
        String invocationInfo = null;
        try {
            if (traceHandler) {
                invocationInfo = S.fmt("%s(%s)", handlerMetaInfo.fullName(), $.toString2(params));
                Trace.LOGGER_HANDLER.trace(invocationInfo);
            }
            result = null == methodAccess ? $.invokeStatic(method, params) : methodAccess.invoke(controller, handlerIndex, params);
            if (returnString && context.acceptJson()) {
                result = null == result ? null : ensureValidJson(S.string(result));
            }
        } catch (Result r) {
            result = r;
        } catch (Exception e) {
            if (traceHandler) {
                Trace.LOGGER_HANDLER.trace(e, "error invoking %s", invocationInfo);
            }
            throw e;
        }
        if (context.resp().isClosed()) {
            return null;
        }
        if (null == result && handler.hasReturn() && !handler.returnTypeInfo().isResult()) {
            // ActFramework respond 404 Not Found when
            // handler invoker return `null`
            // and there are return type of the action method signature
            // and the return type is **NOT** Result
            return ActNotFound.create();
        }
        boolean hasTemplate = checkTemplate(context);
        if (hasTemplate && result instanceof RenderAny) {
            result = RenderTemplate.INSTANCE;
        }
        return Controller.Util.inferResult(handlerMetaInfo, result, context, hasTemplate);
    }

    private String ensureValidJson(String result) {
        if (S.blank(result)) {
            return "{}";
        }
        boolean looksOkay = (result.startsWith("{") && result.endsWith("}")) || (result.startsWith("[") && result.endsWith("]"));
        if (!looksOkay) {
            fullJsonStringCheckFailure = true;
            return "{\"result\":\"" + result + "\"}";
        }
        if (fullJsonStringCheckFailure || !fullJsonStringChecked) {
            fullJsonStringChecked = true;
            try {
                JSON.parse(result);
            } catch (Exception e) {
                fullJsonStringCheckFailure = true;
                return "{\"result\":\"" + result + "\"}";
            }
        }
        return result;
    }

    public boolean checkTemplate(ActionContext context) {
        if (!context.state().isHandling()) {
            //we don't check template on interceptors
            return false;
        }
        H.Format fmt = context.accept();
        if (noTemplateCache || Act.isDev()) {
            return probeTemplate(fmt, context);
        }
        Boolean hasTemplate = context.hasTemplate();
        if (null != hasTemplate) {
            return hasTemplate;
        }
        hasTemplate = templateAvailabilityCache.get(fmt);
        if (null == hasTemplate) {
            hasTemplate = probeTemplate(fmt, context);
            templateAvailabilityCache.putIfAbsent(fmt, hasTemplate);
        }
        context.setHasTemplate(hasTemplate);
        return hasTemplate;
    }

    private boolean probeTemplate(H.Format fmt, ActionContext context) {
        if (!TemplatePathResolver.isAcceptFormatSupported(fmt)) {
            return false;
        } else {
            Template t = Act.viewManager().load(context);
            return t != null;
        }
    }

    private Object[] params(Object controller, ActionContext context) {
        if (0 == paramCount) {
            return DUMP_PARAMS;
        }
        return paramLoaderService.loadMethodParams(controller, method, context);
    }


    public <T extends Annotation> T getAnnotation(Class<T> annoType) {
        T anno = method.getAnnotation(annoType);
        if (null == anno) {
            anno = controllerClass.getAnnotation(annoType);
        }
        return anno;
    }

    public boolean hasAnnotation(Class<? extends Annotation> annoType) {
        return (null != method.getAnnotation(annoType)) || null != controllerClass.getAnnotation(annoType);
    }

    public static ControllerAction createControllerAction(ActionMethodMetaInfo meta, App app) {
        return new ControllerAction(new ReflectedHandlerInvoker(meta, app));
    }

    public static BeforeInterceptor createBeforeInterceptor(InterceptorMethodMetaInfo meta, App app) {
        return new _Before(new ReflectedHandlerInvoker(meta, app));
    }

    public static AfterInterceptor createAfterInterceptor(InterceptorMethodMetaInfo meta, App app) {
        return new _After(new ReflectedHandlerInvoker(meta, app));
    }

    public static ExceptionInterceptor createExceptionInterceptor(CatchMethodMetaInfo meta, App app) {
        return new _Exception(new ReflectedHandlerInvoker(meta, app), meta);
    }

    public static FinallyInterceptor createFinannyInterceptor(InterceptorMethodMetaInfo meta, App app) {
        return new _Finally(new ReflectedHandlerInvoker(meta, app));
    }

    private static class _Before extends BeforeInterceptor {
        private ActionHandlerInvoker invoker;

        _Before(ActionHandlerInvoker invoker) {
            super(invoker.priority());
            this.invoker = invoker;
        }

        @Override
        public Result handle(ActionContext actionContext) throws Exception {
            return invoker.handle(actionContext);
        }

        @Override
        public boolean sessionFree() {
            return invoker.sessionFree();
        }

        @Override
        public boolean express() {
            return invoker.express();
        }

        @Override
        public boolean skipEvents() {
            return invoker.skipEvents();
        }

        @Override
        public void accept(Visitor visitor) {
            invoker.accept(visitor.invokerVisitor());
        }

        @Override
        public CORS.Spec corsSpec() {
            return invoker.corsSpec();
        }

        @Override
        protected void releaseResources() {
            invoker.destroy();
            invoker = null;
        }
    }

    private static class _After extends AfterInterceptor {
        private AfterInterceptorInvoker invoker;

        _After(AfterInterceptorInvoker invoker) {
            super(invoker.priority());
            this.invoker = invoker;
        }

        @Override
        public Result handle(Result result, ActionContext actionContext) throws Exception {
            return invoker.handle(result, actionContext);
        }

        @Override
        public CORS.Spec corsSpec() {
            return invoker.corsSpec();
        }

        @Override
        public boolean sessionFree() {
            return invoker.sessionFree();
        }

        @Override
        public boolean express() {
            return invoker.express();
        }

        @Override
        public boolean skipEvents() {
            return invoker.skipEvents();
        }

        @Override
        public void accept(Visitor visitor) {
            invoker.accept(visitor.invokerVisitor());
        }

        @Override
        public void accept(ActionHandlerInvoker.Visitor visitor) {
            invoker.accept(visitor);
        }

        @Override
        protected void releaseResources() {
            invoker.destroy();
            invoker = null;
        }
    }

    private static class _Exception extends ExceptionInterceptor {
        private ExceptionInterceptorInvoker invoker;

        _Exception(ExceptionInterceptorInvoker invoker, CatchMethodMetaInfo metaInfo) {
            super(invoker.priority(), exceptionClassesOf(metaInfo));
            this.invoker = invoker;
        }

        @SuppressWarnings("unchecked")
        private static List<Class<? extends Exception>> exceptionClassesOf(CatchMethodMetaInfo metaInfo) {
            List<String> classNames = metaInfo.exceptionClasses();
            List<Class<? extends Exception>> clsList;
            clsList = C.newSizedList(classNames.size());
            AppClassLoader cl = App.instance().classLoader();
            for (String cn : classNames) {
                clsList.add((Class) $.classForName(cn, cl));
            }
            return clsList;
        }

        @Override
        protected Result internalHandle(Exception e, ActionContext actionContext) throws Exception {
            return invoker.handle(e, actionContext);
        }

        @Override
        public boolean sessionFree() {
            return invoker.sessionFree();
        }

        @Override
        public boolean express() {
            return invoker.express();
        }

        @Override
        public boolean skipEvents() {
            return invoker.skipEvents();
        }

        @Override
        public void accept(ActionHandlerInvoker.Visitor visitor) {
            invoker.accept(visitor);
        }

        @Override
        public void accept(Visitor visitor) {
            invoker.accept(visitor.invokerVisitor());
        }

        @Override
        public CORS.Spec corsSpec() {
            return invoker.corsSpec();
        }

        @Override
        protected void releaseResources() {
            invoker.destroy();
            invoker = null;
        }
    }

    private static class _Finally extends FinallyInterceptor {
        private ActionHandlerInvoker invoker;

        _Finally(ActionHandlerInvoker invoker) {
            super(invoker.priority());
            this.invoker = invoker;
        }

        @Override
        public void handle(ActionContext actionContext) throws Exception {
            invoker.handle(actionContext);
        }

        @Override
        public CORS.Spec corsSpec() {
            return invoker.corsSpec();
        }

        @Override
        public boolean sessionFree() {
            return invoker.sessionFree();
        }

        @Override
        public boolean express() {
            return invoker.express();
        }

        @Override
        public boolean skipEvents() {
            return invoker.skipEvents();
        }

        @Override
        public void accept(Visitor visitor) {
            invoker.accept(visitor.invokerVisitor());
        }

        @Override
        protected void releaseResources() {
            invoker.destroy();
            invoker = null;
        }
    }

    private static class CacheKeyBuilder extends $.F1<ActionContext, String> {
        private String[] keys;
        private final String base;

        CacheKeyBuilder(CacheFor cacheFor, String actionPath) {
            this.base = base(actionPath);
            this.keys = cacheFor.keys();
        }

        private String base(String actionPath) {
            S.Buffer buffer = S.newBuffer();
            String[] sa = actionPath.split("\\.");
            for (String s : sa) {
                buffer.append(s.charAt(0));
            }
            buffer.append(actionPath.hashCode());
            return buffer.toString();
        }

        @Override
        public String apply(ActionContext context) throws NotAppliedException, $.Break {
            TreeMap<String, String> keyValues = keyValues(context);
            S.Buffer buffer = S.newBuffer(base);
            for (Map.Entry<String, String> entry : keyValues.entrySet()) {
                buffer.append("-").append(entry.getKey()).append(":").append(entry.getValue());
            }
            buffer.append(context.userAgent().isMobile() ? "M" : "B");
            return buffer.toString();
        }

        private TreeMap<String, String> keyValues(ActionContext context) {
            TreeMap<String, String> map = new TreeMap<>();
            if (keys.length > 0) {
                for (String key : keys) {
                    map.put(key, paramVal(key, context));
                }
            } else {
                for (String key : context.paramKeys()) {
                    map.put(key, paramVal(key, context));
                }
            }
            return map;
        }

        private String paramVal(String key, ActionContext context) {
            String[] allValues = context.paramVals(key);
            if (0 == allValues.length) {
                return "";
            } else if (1 == allValues.length) {
                return allValues[0];
            } else {
                return  $.toString2(allValues);
            }
        }
    }

}
