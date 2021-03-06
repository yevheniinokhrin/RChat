package pl.nn44.xmlrpc.common;

import org.apache.ws.commons.util.NamespaceContextImpl;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.common.TypeFactoryImpl;
import org.apache.xmlrpc.common.XmlRpcController;
import org.apache.xmlrpc.common.XmlRpcStreamConfig;
import org.apache.xmlrpc.parser.MapParser;
import org.apache.xmlrpc.parser.TypeParser;
import org.apache.xmlrpc.serializer.MapSerializer;
import org.apache.xmlrpc.serializer.TypeSerializer;
import org.apache.xmlrpc.serializer.TypeSerializerImpl;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.lang.reflect.*;
import java.util.*;

/**
 * Please check {@link pl.nn44.xmlrpc} package doc.
 */
public class AnyTypeFactory extends TypeFactoryImpl {

    protected static final Set<Class<?>> BASIC_CLASSES =
            Collections.unmodifiableSet(new HashSet<Class<?>>(11) {{
                // Integer
                add(Integer.class);
                add(int.class);

                // Boolean
                add(Boolean.class);
                add(boolean.class);

                // String
                add(String.class);

                // Double
                add(Double.class);
                add(double.class);

                // java.util.Date
                add(Date.class);

                // byte[]
                add(byte[].class);

                // java.util.Map
                add(Map.class);

                // Object[]
                // Arrays are manually handled to keep types.
                // add(Object[].class);

                //  java.util.List
                add(List.class);
            }});

    protected static boolean isBasicClass(Class<?> clazz) {
        return BASIC_CLASSES.stream().anyMatch(bs -> bs.isAssignableFrom(clazz));
    }

    // ---------------------------------------------------------------------------------------------------------------

    public AnyTypeFactory(XmlRpcController pController) {
        super(pController);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public TypeSerializer getSerializer(XmlRpcStreamConfig pConfig,
                                        Object pObject)
            throws SAXException {

        if (pObject == null || !isBasicClass(pObject.getClass())) {
            TypeSerializer mapSerializer = new MapSerializer(this, pConfig);

            return new TypeSerializerImpl() {
                @Override
                public void write(ContentHandler pHandler, Object pObject) throws SAXException {
                    try {
                        mapSerializer.write(pHandler, objectToMap(pObject));
                    } catch (Exception e) {
                        throw new SAXException(e.getMessage(), e);
                    }
                }
            };

        } else {
            return super.getSerializer(pConfig, pObject);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public TypeParser getParser(XmlRpcStreamConfig pConfig,
                                NamespaceContextImpl pContext,
                                String pURI,
                                String pLocalName) {

        if (pLocalName.equals("struct")) {
            return new MapParser(pConfig, pContext, this) {

                @Override
                public Object getResult() throws XmlRpcException {
                    try {
                        return mapToObject((Map<?, ?>) super.getResult());
                    } catch (Exception e) {
                        throw new XmlRpcException(e.getMessage(), e);
                    }
                }
            };

        } else {
            return super.getParser(pConfig, pContext, pURI, pLocalName);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    protected Map<String, Object> objectToMap(Object object)
            throws IllegalAccessException {

        Map<String, Object> map = new HashMap<>(10);

        // special case: null value
        if (object == null) {
            map.put("__class__", "null");
            return map;
        }

        Class<?> clazz = object.getClass();

        // special case: enum class
        if (Enum.class.isAssignableFrom(clazz)) {
            map.put("__class__", "enum");
            map.put("type", clazz.getName());
            map.put("name", ((Enum<?>) object).name());
            return map;
        }

        // special case: arrays
        if (clazz.isArray()) {
            map.put("__class__", "array");
            map.put("type", clazz.getComponentType().getName());
            map.put("values", Arrays.asList((Object[]) object));
            return map;
        }

        // any other class: map of (field-name, field-value)
        map.put("__class__", clazz.getName());

        for (Field field : clazz.getDeclaredFields()) {
            // ignored fields
            if (Arrays.asList("serialVersionUID", "__ignore__")
                    .contains(field.getName())) {
                continue;
            }
            // transient fields
            if (Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            field.setAccessible(true);

            String key = field.getName();
            Object value = field.get(object);

            map.put(key, value);
        }

        return map;
    }

    // ---------------------------------------------------------------------------------------------------------------

    protected Object mapToObject(Map<?, ?> map)
            throws
            ClassNotFoundException,
            IllegalAccessException,
            InstantiationException,
            NoSuchFieldException,
            NoSuchMethodException,
            InvocationTargetException {

        Object __class__ = map.get("__class__");

        // special case: not-special-map
        if (__class__ == null) {
            return map;
        }

        // special case: null value
        if (__class__.equals("null")) {
            return null;
        }

        // special case: enum class
        if (__class__.equals("enum")) {
            Class<?> enumType = Class.forName(map.get("type").toString());
            String keyName = map.get("name").toString();

            // or Enum.valueOf((Class<Enum>) enumType, keyName);
            // but then unchecked cast warning
            return Arrays.stream(enumType.getEnumConstants())
                    .map(ec -> (Enum<?>) ec)
                    .filter(ec -> ec.name().equals(keyName))
                    .findFirst()
                    .orElseThrow(() -> new InstantiationException(
                            "enum=" + map.get("type") + ';' + map.get("name")
                    ));
        }

        // special case: array
        if (__class__.equals("array")) {
            Class<?> type = Class.forName(map.get("type").toString());
            Object[] values = (Object[]) map.get("values");

            Object[] newValues = (Object[]) Array.newInstance(type, values.length);
            System.arraycopy(values, 0, newValues, 0, values.length);
            return newValues;
        }

        Class<?> clazz = Class.forName(__class__.toString());

        // new instance
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);

        Object instance = constructor.newInstance();

        // fill all given fields
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey().equals("__class__")) {
                continue;
            }

            Field field = clazz.getDeclaredField(entry.getKey().toString());
            field.setAccessible(true);

            // write also to finals
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);

            Object value = entry.getValue();

            field.set(instance, value);
        }

        return instance;
    }
}
