package act.db;

import act.Act;
import act.app.App;
import act.plugin.AppServicePlugin;
import act.util.ClassNode;
import org.osgl.$;
import org.osgl.Osgl;
import org.osgl.exception.NotAppliedException;
import org.osgl.inject.BeanSpec;
import org.osgl.inject.Injector;
import org.osgl.util.E;
import org.osgl.util.S;
import org.osgl.util.ValueObject;

import java.beans.Transient;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The `AdaptiveRecord` interface specifies a special {@link Model} in that
 * the fields/columns could be implicitly defined by database
 */
public interface AdaptiveRecord<ID_TYPE, MODEL_TYPE extends AdaptiveRecord> extends Model<ID_TYPE, MODEL_TYPE> {

    /**
     * Add or replace a key/val pair into the active record
     *
     * @param key the key
     * @param val the value
     * @return the active record instance
     */
    MODEL_TYPE putValue(String key, Object val);

    /**
     * Merge a key/val pair in the active record.
     * <p>
     * If the key specified does not exists then insert the key/val pair into the record.
     * <p>
     * If there are existing key/val pair then merge it with the new one:
     * * if the val is simple type or cannot be merged, then replace the existing value with new value
     * * if the val can be merged, e.g. it is a POJO or another adaptive record, then merge the new value into
     * the old value. Merge shall happen recursively
     *
     * @param key the key
     * @param val the value
     * @return the active record instance
     */
    MODEL_TYPE mergeValue(String key, Object val);

    /**
     * Add all key/val pairs from specified kv map into this active record
     *
     * @param kvMap the key/value pairs
     * @return this active record instance
     */
    MODEL_TYPE putValues(Map<String, Object> kvMap);

    /**
     * Merge all key/val pairs from specified kv map into this active record
     *
     * @param kvMap the key/value pairs
     * @return this active record instance
     * @see #mergeValue(String, Object)
     */
    MODEL_TYPE mergeValues(Map<String, Object> kvMap);

    /**
     * Get value from the active record by key specified
     *
     * @param key the key
     * @param <T> the generic type of the value
     * @return the value or `null` if not found
     */
    <T> T getValue(String key);

    /**
     * Export the key/val pairs from this active record into a map
     *
     * @return the exported map contains all key/val pairs stored in this active record
     */
    Map<String, Object> toMap();

    /**
     * Get the size of the data stored in the active record
     *
     * @return the active record size
     */
    int size();

    /**
     * Check if the active records has a value associated with key specified
     *
     * @param key the key
     * @return `true` if there is value associated with the key in the record, or `false` otherwise
     */
    boolean containsKey(String key);

    /**
     * Returns a set of keys that has value stored in the active record
     *
     * @return the key set
     */
    Set<String> keySet();

    /**
     * Returns a set of entries stored in the active record
     *
     * @return the entry set
     */
    Set<Map.Entry<String, Object>> entrySet();

    /**
     * Returns a set of entries stored in the active record. For
     * field entries, use the field filter specified to check
     * if it needs to be added into the return set
     *
     * @param fieldFilter the function that returns `true` or `false` for
     *                    bean spec of a certain field declared in the class
     * @return the entry set with field filter applied
     */
    Set<Map.Entry<String, Object>> entrySet($.Function<BeanSpec, Boolean> fieldFilter);

    /**
     * Returns a Map typed object backed by this active record
     *
     * @return a Map backed by this active record
     */
    Map<String, Object> asMap();

    /**
     * Returns the meta info of this AdaptiveRecord
     *
     * @return
     */
    @Transient
    MetaInfo metaInfo();

    class MetaInfo {
        private Class<? extends AdaptiveRecord> arClass;
        public String className;
        public Map<String, BeanSpec> getterFieldSpecs;
        public Map<String, Class> getterFieldClasses;
        public Map<String, BeanSpec> setterFieldSpecs;
        public Map<String, Class> setterFieldClasses;
        public Map<String, $.Function> fieldGetters;
        public Map<String, $.Func2> fieldSetters;
        public Map<String, $.Func2> fieldMergers;

        public MetaInfo(Class<? extends AdaptiveRecord> clazz) {
            this.className = clazz.getName();
            this.arClass = clazz;
            this.discoverProperties(clazz);
        }

        @Deprecated
        public Class fieldClass(String fieldName) {
            Class clazz = setterFieldClasses.get(fieldName);
            return null == clazz ? getterFieldClasses.get(fieldName) : clazz;
        }

        public Class getterFieldClass(String fieldName) {
            return getterFieldClasses.get(fieldName);
        }

        public Class setterFieldClass(String fieldName) {
            return setterFieldClasses.get(fieldName);
        }

        public Type getterFieldType(String fieldName) {
            BeanSpec spec = getterFieldSpecs.get(fieldName);
            return null == spec ? null : spec.type();
        }

        public Type setterFieldType(String fieldName) {
            BeanSpec spec = setterFieldSpecs.get(fieldName);
            return null == spec ? null : spec.type();
        }

        private void discoverProperties(Class<? extends AdaptiveRecord> clazz) {
            getterFieldSpecs = new HashMap<>();
            getterFieldClasses = new HashMap<>();
            setterFieldSpecs = new HashMap<>();
            setterFieldClasses = new HashMap<>();
            fieldGetters = new HashMap<>();
            fieldSetters = new HashMap<>();
            fieldMergers = new HashMap<>();
            Injector injector = Act.app().injector();
            for (final Method m : clazz.getMethods()) {
                String name = propertyName(m);
                if (S.blank(name)) {
                    continue;
                } else {
                    name = S.lowerFirst(name);
                    if ("idAsStr".equals(name)) {
                        // special case for MorphiaModel
                        continue;
                    }
                }
                Class returnClass = m.getReturnType();
                Type returnType = m.getGenericReturnType();
                Class paramClass = null;
                Type paramType = null;
                Class[] params = m.getParameterTypes();
                Type[] paramTypes = m.getGenericParameterTypes();
                if (null != params && params.length == 1) {
                    paramClass = params[0];
                    paramType = paramTypes[0];
                }
                Class fieldClass = null == paramClass ? returnClass : paramClass;
                Type fieldType = null == paramType ? returnType : paramType;
                if (!(fieldType instanceof ParameterizedType)) {
                    fieldType = fieldClass;
                }
                if (null == paramClass) {
                    getterFieldSpecs.put(name, BeanSpec.of(fieldType, m.getDeclaredAnnotations(), name, injector));
                    getterFieldClasses.put(name, fieldClass);
                } else {
                    BeanSpec existingSpec = setterFieldSpecs.get(name);
                    if (null != existingSpec) {
                        // we need to infer the type from field in this case
                        Field field = $.fieldOf(clazz, name, true);
                        if (null != field) {
                            setterFieldSpecs.put(name, BeanSpec.of(field, injector));
                            setterFieldClasses.put(name, field.getType());
                        } else {
                            if (fieldClass == Object.class) {
                                // ignore
                            } else if (existingSpec.rawType() == Object.class) {
                                setterFieldSpecs.put(name, BeanSpec.of(fieldType, m.getDeclaredAnnotations(), name, injector));
                                setterFieldClasses.put(name, fieldClass);
                            }
                        }
                    } else {
                        setterFieldSpecs.put(name, BeanSpec.of(fieldType, m.getDeclaredAnnotations(), name, injector));
                        setterFieldClasses.put(name, fieldClass);
                    }
                }
                if (null != paramClass) {
                    final String fieldName = name;
                    fieldSetters.put(name, new Osgl.Func2() {
                        @Override
                        public Object apply(Object host, Object value) throws NotAppliedException, Osgl.Break {
                            BeanSpec spec = setterFieldSpecs.get(fieldName);
                            if (null != value && !spec.isInstance(value)) {
                                if (value instanceof String) {
                                    value = Act.app().resolverManager().resolve((String)value, spec.rawType());
                                } else {
                                    throw new IllegalArgumentException(S.concat("Type mismatch. Expected: ", spec.rawType().getName(), "found: ", S.string(value)));
                                }
                            }
                            $.invokeVirtual(host, m, value);
                            return null;
                        }
                    });
                } else {
                    fieldGetters.put(name, new Osgl.F1() {
                        @Override
                        public Object apply(Object host) throws NotAppliedException, Osgl.Break {
                            try {
                                return m.invoke(host);
                            } catch (InvocationTargetException e) {
                                throw E.unexpected(e.getTargetException());
                            } catch (IllegalAccessException e) {
                                throw E.unexpected(e);
                            }
                        }
                    });
                }
            }
        }

        private String propertyName(Method m) {
            String name = m.getName();
            Type[] paramTypes = m.getGenericParameterTypes();
            if (name.startsWith("set") && void.class == m.getReturnType() && null != paramTypes && paramTypes.length == 1) {
                return name.substring(3);
            }
            boolean isGet = name.startsWith("get");
            boolean isIs = name.startsWith("is");
            if ((isGet || isIs) && void.class != m.getReturnType() && (null == paramTypes || paramTypes.length == 0)) {
                return isGet ? name.substring(3) : name.substring(2);
            }
            return null;
        }

        public static Object merge(Object to, Object from) {
            if (null == to) {
                return from;
            }
            if (null == from) {
                return to;
            }
            if (canBeMerged(to.getClass())) {
                return _merge(to, from);
            }
            return from;
        }

        private static Object _merge(Object to, Object from) {
            if (to instanceof ValueObject) {
                if (from instanceof ValueObject) {
                    return ValueObject.of(merge(((ValueObject) to).value(), ((ValueObject) from).value()));
                }
                return ValueObject.of(merge(((ValueObject) to).value(), from));
            }
            if (to instanceof AdaptiveRecord) {
                AdaptiveRecord ar = (AdaptiveRecord) to;
                return mergeIntoAdaptiveRecord(ar, from);
            }
            if (to instanceof Map) {
                Map map = (Map) to;
                return mergeIntoMap(map, from);
            }
            if (to instanceof Set) {
                Set set = (Set) to;
                return mergeIntoSet(set, from);
            }
            if (to instanceof List) {
                List list = (List) to;
                return mergeIntoList(list, from);
            }
            if (to.getClass().isArray()) {
                List list = new ArrayList();
                int len = Array.getLength(to);
                for (int i = 0; i < len; ++i) {
                    list.add(Array.get(to, i));
                }
                List list1 = mergeIntoList(list, from);
                int sz1 = list1.size();
                Object a1 = Array.newInstance(to.getClass().getComponentType(), sz1);
                for (int i = 0; i < sz1; ++i) {
                    Array.set(a1, i, list1.get(i));
                }
                return a1;
            }
            return mergeIntoPojo(to, from);
        }

        private static String getterName(Field field) {
            boolean isBoolean = field.getType() == Boolean.class || field.getType() == boolean.class;
            return (isBoolean ? "is" : "get") + S.capFirst(field.getName());
        }

        private static String setterName(Field field) {
            return "set" + S.capFirst(field.getName());
        }

        private static boolean canBeMerged(Class<?> c) {
            return !($.isSimpleType(c) || isDateType(c));
        }

        private static boolean isDateType(Class<?> c) {
            String name = c.getSimpleName();
            return (name.endsWith("Date") || name.endsWith("DateTime") || name.endsWith("Calendar"));
        }

        private static AdaptiveRecord mergeIntoAdaptiveRecord(AdaptiveRecord ar, Object value) {
            if (value instanceof Map) {
                return mergeMapIntoAdaptiveRecord(ar, (Map) value);
            }
            if (value instanceof AdaptiveRecord) {
                return mergeMapIntoAdaptiveRecord(ar, ((AdaptiveRecord) value).asMap());
            }
            List<Field> fields = $.fieldsOf(value.getClass(), true);
            for (Field f : fields) {
                f.setAccessible(true);
                try {
                    String fn = f.getName();
                    Object fv = f.get(value);
                    Object o0 = ar.getValue(fn);
                    ar.putValue(fn, merge(o0, fv));
                } catch (IllegalAccessException e) {
                    throw E.unexpected(e, "error merging into adaptive record");
                }
            }
            return ar;
        }

        private static AdaptiveRecord mergeMapIntoAdaptiveRecord(AdaptiveRecord ar, Map map) {
            return ar.putValues(map);
        }

        private static Map mergeIntoMap(Map map, Object value) {
            if (value instanceof Map) {
                return mergeMapIntoMap(map, (Map) value);
            }
            if (value instanceof AdaptiveRecord) {
                return mergeMapIntoMap(map, ((AdaptiveRecord) value).asMap());
            }
            Map retval = new HashMap(map);
            List<Field> fields = $.fieldsOf(value.getClass(), true);
            for (Field f : fields) {
                f.setAccessible(true);
                try {
                    String fn = f.getName();
                    Object fv = f.get(value);
                    Object o0 = map.get(fn);
                    retval.put(fn, merge(o0, fv));
                } catch (IllegalAccessException e) {
                    throw E.unexpected(e, "error merging into adaptive record");
                }
            }
            return retval;
        }

        private static Map mergeMapIntoMap(Map m0, Map<?, ?> m1) {
            Map retval = (Map) Act.injector().get(m0.getClass());
            retval.putAll(m0);
            for (Map.Entry entry : m1.entrySet()) {
                Object k = entry.getKey();
                Object v = entry.getValue();
                retval.put(k, merge(m0.get(k), v));
            }
            return retval;
        }

        private static Set mergeIntoSet(Set set, Object value) {
            if (value instanceof Collection) {
                return mergeCollectionIntoSet(set, (Collection) value);
            }
            if (value.getClass().isArray()) {
                List list = new ArrayList();
                int len = Array.getLength(value);
                for (int i = 0; i < len; ++i) {
                    list.add(Array.get(value, i));
                }
                return mergeCollectionIntoSet(set, list);
            }
            throw new IllegalArgumentException("Cannot merge " + value.getClass() + " into Set");
        }

        private static Set mergeCollectionIntoSet(Set set, Collection col) {
            Set set1 = (Set) Act.injector().get(set.getClass());
            set1.addAll(col);
            return set1;
        }

        private static List mergeIntoList(List list, Object value) {
            if (value instanceof Set) {
                return mergeSetIntoList(list, (Set) value);
            }
            if (value instanceof List) {
                return mergeListIntoList(list, (List) value);
            }
            if (value.getClass().isArray()) {
                List list0 = new ArrayList();
                int len = Array.getLength(value);
                for (int i = 0; i < len; ++i) {
                    list0.add(Array.get(value, i));
                }
                return mergeListIntoList(list, list0);
            }
            throw new IllegalArgumentException("Cannot merge " + value.getClass() + " into List");
        }

        private static List mergeSetIntoList(List list, Set set) {
            List retval = (List) Act.injector().get(list.getClass());
            retval.addAll(list);
            retval.addAll(set);
            return retval;
        }

        private static List mergeListIntoList(List to, List from) {
            List retval = (List) Act.injector().get(to.getClass());
            int szTo = to.size();
            int szFrom = from.size();
            int szMin = Math.min(szTo, szFrom);
            for (int i = 0; i < szMin; ++i) {
                Object o0 = to.get(i);
                Object o1 = from.get(i);
                retval.add(merge(o0, o1));
            }
            if (szTo > szFrom) {
                for (int i = szMin; i < szTo; ++i) {
                    retval.add(to.get(i));
                }
            } else if (szFrom > szTo) {
                for (int i = szMin; i < szFrom; ++i) {
                    retval.add(from.get(i));
                }
            }
            return retval;
        }

        private static Object mergeIntoPojo(Object o0, Object o1) {
            if (o1 instanceof Map) {
                return mergeMapIntoPojo(o0, (Map) o1);
            }
            if (o1 instanceof AdaptiveRecord) {
                return mergeMapIntoPojo(o0, ((AdaptiveRecord) o1).asMap());
            }
            if (o1 instanceof Collection || o1.getClass().isArray()) {
                throw E.unexpected("cannot merge " + o1.getClass() + " into " + o0.getClass());
            }
            Class<?> c0 = o0.getClass();
            List<Field> fields = $.fieldsOf(o1.getClass(), true);
            for (Field f : fields) {
                f.setAccessible(true);
                try {
                    String fn = f.getName();
                    Field f0 = $.fieldOf(c0, fn);
                    if (null == f0) {
                        continue;
                    }
                    Object fv = f.get(o1);
                    f0.setAccessible(true);
                    Object fv0 = merge(f0.get(o0), fv);
                    f0.set(o0, fv0);
                } catch (IllegalAccessException e) {
                    throw E.unexpected(e, "error merging into POJO");
                }
            }
            return o0;
        }

        private static Object mergeMapIntoPojo(Object o0, Map map) {
            List<Field> fields = $.fieldsOf(o0.getClass(), true);
            for (Field f : fields) {
                String fn = f.getName();
                if (map.containsKey(fn)) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(o0);
                        f.set(o0, merge(v, map.get(fn)));
                    } catch (IllegalAccessException e) {
                        throw E.unexpected(e, "error merging into POJO");
                    }
                }
            }
            return o0;
        }

        public static class Repository extends AppServicePlugin {
            @Override
            protected void applyTo(App app) {
            }

            private ConcurrentMap<Class<?>, MetaInfo> map = new ConcurrentHashMap<>();

            public MetaInfo get(Class<? extends AdaptiveRecord> clazz, $.Function<Class<? extends AdaptiveRecord>, MetaInfo> factory) {
                MetaInfo info = map.get(clazz);
                if (null == info) {
                    info = factory.apply(clazz);
                    map.putIfAbsent(clazz, info);
                }
                return info;
            }
        }
    }

}
