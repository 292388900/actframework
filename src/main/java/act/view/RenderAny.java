package act.view;

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

import act.app.ActionContext;
import org.osgl.http.H;
import org.osgl.mvc.result.RenderBinary;
import org.osgl.mvc.result.RenderContent;
import org.osgl.mvc.result.Result;
import org.osgl.mvc.result.RichResult;
import org.osgl.storage.ISObject;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;

import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.osgl.http.H.Format.*;

/**
 * Render a template with template path and arguments provided
 * by {@link ActionContext}
 */
public class RenderAny extends Result {

    public static final RenderAny INSTANCE = new RenderAny();

    public RenderAny() {
        super(H.Status.OK);
    }

    // TODO: Allow plugin to support rendering pdf, xls or other binary types
    public void apply(ActionContext context) {
        Boolean hasTemplate = context.hasTemplate();
        if (null != hasTemplate && hasTemplate) {
            RenderTemplate.get(context.successStatus()).apply(context);
            return;
        }
        H.Format fmt = context.accept();
        if (fmt == UNKNOWN) {
            H.Request req = context.req();
            H.Method method = req.method();
            String methodInfo = S.concat(method.name(), " method to ");
            String acceptHeader = req.header(H.Header.Names.ACCEPT);
            throw E.unsupport(S.concat(
                    "Unknown accept content type(",
                    acceptHeader,
                    "): ",
                    methodInfo,
                    req.url()));
        }
        RichResult result = null;
        if (JSON == fmt) {
            List<String> varNames = context.__appRenderArgNames();
            Map<String, Object> map = new HashMap<>(context.renderArgs());
            if (null != varNames && !varNames.isEmpty()) {
                for (String name : varNames) {
                    map.put(name, context.renderArg(name));
                }
            }
            result = RenderContent.renderJson(map);
        } else if (XML == fmt) {
            List<String> varNames = context.__appRenderArgNames();
            Map<String, Object> map = C.newMap();
            if (null != varNames && !varNames.isEmpty()) {
                for (String name : varNames) {
                    map.put(name, context.renderArg(name));
                }
            }
            result = new FilteredRenderXML(map, null, context);
        } else if (HTML == fmt || TXT == fmt || CSV == fmt) {
            if (null != hasTemplate) {
                throw E.unsupport("Template[%s] not found", context.templatePath());
            }
            // try render template again as handler might changed the template path
            // in the code
            RenderTemplate.get(context.successStatus()).apply(context);
            return;
        } else if (PDF == fmt || XLS == fmt || XLSX == fmt || DOC == fmt || DOCX == fmt) {
            List<String> varNames = context.__appRenderArgNames();
            if (null != varNames && !varNames.isEmpty()) {
                Object firstVar = context.renderArg(varNames.get(0));
                String action = S.str(context.actionPath()).afterLast(".").toString();
                if (firstVar instanceof File) {
                    File file = (File) firstVar;
                    result = new RenderBinary().source(file).name(action);
                } else if (firstVar instanceof InputStream) {
                    InputStream is = (InputStream) firstVar;
                    result = new RenderBinary().source(is).name(action);
                } else if (firstVar instanceof ISObject) {
                    ISObject sobj = (ISObject) firstVar;
                    result = new RenderBinary().source(sobj).name(action);
                }
                if (null == result) {
                    throw E.unsupport("Unknown render arg type [%s] for binary response", firstVar.getClass());
                }
            } else {
                throw E.unexpected("No render arg found for binary response");
            }
        }
        if (null != result) {
            result.overwriteStatus(context.successStatus()).apply(context);
        } else {
            throw E.unexpected("Unknown accept content type: %s", fmt.contentType());
        }
    }

    public static RenderAny get() {
        return INSTANCE;
    }

    public static void clearThreadLocals() {
        RenderTemplate.renderArgsBag.remove();
    }

}
