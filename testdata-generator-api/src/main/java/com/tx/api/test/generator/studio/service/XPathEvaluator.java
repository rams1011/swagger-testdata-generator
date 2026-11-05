package com.tx.api.test.generator.studio.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;

/**
 * XPath assertions on XML responses, stored beside JSONPath ones as {@code xpath:<expression>=<check>}, for example
 * {@code xpath://role[code='TestCode']/name=Test} or {@code xpath:count(//role)=op:gte:1}. One node is a single
 * value (its trimmed text); several nodes form a list, with the same list rules as JSONPath; an expression that
 * returns a number, string or boolean (count(), name(), ...) is a single value. The document is parsed without
 * namespaces, so {@code //role} matches {@code <role>} under a default namespace; use {@code local-name()} for
 * prefixed names.
 */
final class XPathEvaluator {

    static final String PREFIX = "xpath:";

    private XPathEvaluator() {
    }

    static boolean isXPath(String path) {
        return path != null && path.trim().regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    static String expression(String path) {
        return path.trim().substring(PREFIX.length()).trim();
    }

    static JsonPathEvaluator.Check check(String body, String path, String expected) {
        Document document = parse(body);
        if (document == null) {
            return new JsonPathEvaluator.Check("(not XML)", false);
        }
        String expression = expression(path);
        if (expression.isEmpty()) {
            return new JsonPathEvaluator.Check("(invalid XPath: empty expression)", false);
        }
        XPath xpath = XPathFactory.newInstance().newXPath();
        try {
            NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
            if (nodes.getLength() == 0) {
                return JsonPathEvaluator.checkRead(null, true, expected);
            }
            if (nodes.getLength() == 1) {
                return JsonPathEvaluator.checkRead(text(nodes.item(0)), true, expected);
            }
            JsonArray matches = new JsonArray();
            for (int i = 0; i < nodes.getLength(); i++) {
                matches.add(text(nodes.item(i)));
            }
            return JsonPathEvaluator.checkRead(matches, false, expected);
        } catch (XPathExpressionException notNodes) {
            try {
                String value = (String) xpath.evaluate(expression, document, XPathConstants.STRING);
                return JsonPathEvaluator.checkRead(new JsonPrimitive(value), true, expected);
            } catch (XPathExpressionException exception) {
                String message = StringUtils.defaultIfBlank(rootMessage(exception), "syntax error");
                return new JsonPathEvaluator.Check("(invalid XPath: " + message + ")", false);
            }
        }
    }

    private static JsonElement text(Node node) {
        return new JsonPrimitive(StringUtils.defaultString(node.getTextContent()).trim());
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    static Document parse(String body) {
        if (StringUtils.isBlank(body) || !body.trim().startsWith("<")) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            return builder.parse(new InputSource(new StringReader(body.trim())));
        } catch (Exception notXml) {
            return null;
        }
    }
}
