package com.tx.api.test.generator.studio.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class XmlPayloadSupport {

    private static final Gson JSON = new Gson();

    private XmlPayloadSupport() {
    }

    static boolean isXml(String contentType) {
        if (StringUtils.isBlank(contentType)) {
            return false;
        }
        String value = contentType.trim().toLowerCase(Locale.ROOT).split(";")[0].trim();
        return "xml".equals(value)
                || "application/xml".equals(value)
                || "text/xml".equals(value)
                || value.endsWith("+xml");
    }

    static boolean looksLikeXml(String text) {
        return StringUtils.isNotBlank(text) && text.trim().startsWith("<");
    }

    static String toXml(Object body, Map<String, Object> schema) {
        if (body == null) {
            return null;
        }
        if (body instanceof String text) {
            if (StringUtils.isBlank(text) || "NA".equalsIgnoreCase(text.trim())) {
                return null;
            }
            if (looksLikeXml(text)) {
                return pretty(text);
            }
            Object parsed = parseJsonObject(text);
            if (parsed != null) {
                return toXml(parsed, schema);
            }
            return wrapText(text, schema);
        }
        try {
            Document document = documentFactory().newDocumentBuilder().newDocument();
            Object unwrapped = unwrapRoot(body, schema);
            Element root = createElement(document, schema, inferRootName(unwrapped, schema));
            applyNamespace(root, xmlMeta(schema));
            document.appendChild(root);
            writeContent(document, root, unwrapped, schema);
            return serialize(document);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to build XML body: " + exception.getMessage());
        }
    }

    static Object fromXml(String xml, Map<String, Object> schema) {
        if (StringUtils.isBlank(xml)) {
            return null;
        }
        try {
            Document document = documentFactory().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return elementToValue(document.getDocumentElement(), schema);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid XML: " + exception.getMessage());
        }
    }

    static boolean xmlEquals(String left, String right, Map<String, Object> schema) {
        if (StringUtils.isBlank(left) || StringUtils.isBlank(right)) {
            return false;
        }
        try {
            JsonElement expected = JSON.toJsonTree(parseBody(left, schema));
            JsonElement actual = JSON.toJsonTree(parseBody(right, schema));
            return expected.equals(actual);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Object parseBody(String text, Map<String, Object> schema) {
        if (looksLikeXml(text)) {
            return fromXml(text, schema);
        }
        Object parsed = parseJsonObject(text);
        return parsed == null ? text.trim() : parsed;
    }

    private static Object parseJsonObject(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject() || parsed.isJsonArray()) {
                return JSON.fromJson(parsed, Object.class);
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return null;
    }

    private static String wrapText(String text, Map<String, Object> schema) {
        String name = elementName(schema, "Request");
        return "<" + name + ">" + escape(text) + "</" + name + ">";
    }

    private static Object unwrapRoot(Object body, Map<String, Object> schema) {
        if (!(body instanceof Map<?, ?> map) || map.size() != 1) {
            return body;
        }
        String root = elementName(schema, null);
        Object only = map.values().iterator().next();
        Object key = map.keySet().iterator().next();
        if (root != null && root.equals(String.valueOf(key)) && only instanceof Map<?, ?>) {
            return only;
        }
        return body;
    }

    private static void writeContent(Document document, Element parent, Object value, Map<String, Object> schema) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> properties = propertiesOf(schema);
            map.forEach((rawKey, child) -> {
                if (rawKey == null) {
                    return;
                }
                String key = String.valueOf(rawKey);
                Map<String, Object> propertySchema = propertySchema(properties, key);
                if (isAttribute(propertySchema)) {
                    if (child != null) {
                        parent.setAttribute(elementName(propertySchema, key), String.valueOf(child));
                    }
                    return;
                }
                if (child instanceof List<?> list) {
                    writeArray(document, parent, key, list, propertySchema);
                    return;
                }
                Element element = createElement(document, propertySchema, key);
                applyNamespace(element, xmlMeta(propertySchema));
                if (child instanceof Map<?, ?> || isObjectSchema(propertySchema)) {
                    writeContent(document, element, child, propertySchema);
                } else if (child != null) {
                    element.setTextContent(String.valueOf(child));
                }
                parent.appendChild(element);
            });
            return;
        }
        if (value instanceof List<?> list) {
            writeArray(document, parent, elementName(schema, "item"), list, schema);
            return;
        }
        parent.setTextContent(String.valueOf(value));
    }

    private static void writeArray(Document document, Element parent, String key, List<?> list,
                                   Map<String, Object> schema) {
        Map<String, Object> items = mapOf(schema.get("items"));
        String itemName = elementName(items, elementName(schema, key));
        boolean wrapped = boolMeta(xmlMeta(schema), "wrapped");
        Element target = parent;
        if (wrapped) {
            target = createElement(document, schema, key);
            applyNamespace(target, xmlMeta(schema));
            parent.appendChild(target);
        }
        for (Object item : list) {
            Element element = createElement(document, items.isEmpty() ? schema : items, itemName);
            applyNamespace(element, xmlMeta(items.isEmpty() ? schema : items));
            if (item instanceof Map<?, ?> || item instanceof List<?> || isObjectSchema(items)) {
                writeContent(document, element, item, items);
            } else if (item != null) {
                element.setTextContent(String.valueOf(item));
            }
            target.appendChild(element);
        }
    }

    private static Object elementToValue(Element element, Map<String, Object> schema) {
        Map<String, Object> properties = propertiesOf(schema);
        Map<String, Object> object = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attr = attributes.item(i);
            String name = localName(attr.getNodeName());
            if (name.startsWith("xmlns")) {
                continue;
            }
            object.put(name, coerce(attr.getNodeValue(), propertySchema(properties, name)));
        }
        Map<String, List<Element>> grouped = new LinkedHashMap<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            grouped.computeIfAbsent(localName(child.getTagName()), ignored -> new ArrayList<>()).add(child);
        }
        if (grouped.isEmpty()) {
            String text = StringUtils.trimToEmpty(element.getTextContent());
            if (object.isEmpty()) {
                return coerce(text, schema);
            }
            if (StringUtils.isNotBlank(text)) {
                object.put("#text", text);
            }
            return object;
        }
        grouped.forEach((name, elements) -> {
            Map<String, Object> propertySchema = propertySchema(properties, name);
            boolean array = isArraySchema(propertySchema) || boolMeta(xmlMeta(propertySchema), "wrapped")
                    || elements.size() > 1;
            Map<String, Object> itemSchema = isArraySchema(propertySchema) ? mapOf(propertySchema.get("items"))
                    : propertySchema;
            if (boolMeta(xmlMeta(propertySchema), "wrapped") && elements.size() == 1) {
                object.put(propertyKey(properties, name), unwrapWrapped(elements.get(0), propertySchema));
                return;
            }
            List<Object> values = new ArrayList<>();
            for (Element child : elements) {
                values.add(elementToValue(child, itemSchema.isEmpty() ? propertySchema : itemSchema));
            }
            String key = propertyKey(properties, name);
            object.put(key, array ? values : values.get(0));
        });
        return object;
    }

    private static Object unwrapWrapped(Element wrapper, Map<String, Object> schema) {
        Map<String, Object> items = mapOf(schema.get("items"));
        List<Object> values = new ArrayList<>();
        NodeList children = wrapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                values.add(elementToValue((Element) node, items));
            }
        }
        return values;
    }

    private static Element createElement(Document document, Map<String, Object> schema, String fallback) {
        Map<String, Object> xml = xmlMeta(schema);
        String name = elementName(schema, fallback);
        String namespace = stringMeta(xml, "namespace");
        String prefix = stringMeta(xml, "prefix");
        if (StringUtils.isNotBlank(namespace) && StringUtils.isNotBlank(prefix)) {
            return document.createElementNS(namespace, prefix + ":" + name);
        }
        if (StringUtils.isNotBlank(namespace)) {
            return document.createElementNS(namespace, name);
        }
        return document.createElement(name);
    }

    private static void applyNamespace(Element element, Map<String, Object> xml) {
        String namespace = stringMeta(xml, "namespace");
        String prefix = stringMeta(xml, "prefix");
        if (StringUtils.isBlank(namespace)) {
            return;
        }
        if (StringUtils.isNotBlank(prefix)) {
            element.setAttribute("xmlns:" + prefix, namespace);
        } else if (StringUtils.isBlank(element.getAttribute("xmlns"))) {
            element.setAttribute("xmlns", namespace);
        }
    }

    private static String inferRootName(Object value, Map<String, Object> schema) {
        String named = elementName(schema, null);
        if (named != null) {
            return named;
        }
        String title = schema == null ? null : stringMeta(schema, "title");
        if (StringUtils.isNotBlank(title)) {
            return safeName(title, "Request");
        }
        return "Request";
    }

    private static String elementName(Map<String, Object> schema, String fallback) {
        String named = stringMeta(xmlMeta(schema), "name");
        if (StringUtils.isNotBlank(named)) {
            return safeName(named, fallback == null ? "Request" : fallback);
        }
        return fallback == null ? null : safeName(fallback, "Request");
    }

    private static String propertyKey(Map<String, Object> properties, String xmlName) {
        if (properties.containsKey(xmlName)) {
            return xmlName;
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (xmlName.equals(stringMeta(xmlMeta(mapOf(entry.getValue())), "name"))) {
                return entry.getKey();
            }
        }
        return xmlName;
    }

    private static Map<String, Object> propertySchema(Map<String, Object> properties, String name) {
        if (properties.containsKey(name)) {
            return mapOf(properties.get(name));
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> candidate = mapOf(entry.getValue());
            if (name.equals(stringMeta(xmlMeta(candidate), "name"))) {
                return candidate;
            }
        }
        return Map.of();
    }

    private static boolean isAttribute(Map<String, Object> schema) {
        return boolMeta(xmlMeta(schema), "attribute");
    }

    private static boolean isObjectSchema(Map<String, Object> schema) {
        return schema != null && ("object".equals(schema.get("type")) || schema.containsKey("properties"));
    }

    private static boolean isArraySchema(Map<String, Object> schema) {
        return schema != null && ("array".equals(schema.get("type")) || schema.containsKey("items"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of();
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, value) -> typed.put(String.valueOf(key), value));
            return typed;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> xmlMeta(Map<String, Object> schema) {
        return schema == null ? Map.of() : mapOf(schema.get("xml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, child) -> typed.put(String.valueOf(key), child));
            return typed;
        }
        return new LinkedHashMap<>();
    }

    private static boolean boolMeta(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String stringMeta(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String safeName(String name, String fallback) {
        String cleaned = StringUtils.defaultString(name).trim()
                .replaceAll("[^A-Za-z0-9._:-]", "_");
        if (cleaned.isEmpty()) {
            return fallback;
        }
        if (!Character.isLetter(cleaned.charAt(0)) && cleaned.charAt(0) != '_' && cleaned.charAt(0) != ':') {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    private static String localName(String name) {
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static Object coerce(String text, Map<String, Object> schema) {
        if (text == null) {
            return "";
        }
        String type = schema == null ? null : String.valueOf(schema.get("type"));
        if ("integer".equals(type) || "number".equals(type)) {
            try {
                return "integer".equals(type) ? Long.parseLong(text.trim()) : Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        if ("boolean".equals(type)) {
            return Boolean.parseBoolean(text.trim());
        }
        return text;
    }

    private static String pretty(String xml) {
        try {
            Document document = documentFactory().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            document.getDocumentElement().normalize();
            return serialize(document);
        } catch (Exception exception) {
            return xml.trim();
        }
    }

    private static String serialize(Document document) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString().trim();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to serialize XML: " + exception.getMessage());
        }
    }

    private static DocumentBuilderFactory documentFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // best-effort hardening; builders still work if a feature is unsupported
        }
        return factory;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
