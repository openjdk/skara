// Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.

package org.openjdk.skara.bots.censussync;

import org.w3c.dom.*;
import org.xml.sax.*;

import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class XML {
    static Document parse(String p) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new ByteArrayInputStream(p.getBytes(StandardCharsets.UTF_8))));
        } catch (ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    static List<Element> children(Element element, String name) {
        var result = new ArrayList<Element>();

        var nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if (child.getTagName().equals(name)) {
                    result.add(child);
                }
            }
        }

        return result;
    }

    static List<Element> children(Document document, String name) {
        var result = new ArrayList<Element>();

        var nodes = document.getElementsByTagName(name);
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }

        return result;
    }

    private static Element single(List<Element> elements) {
        if (elements.size() > 1) {
            throw new IllegalArgumentException("Too many children with name");
        }

        return elements.isEmpty() ? null : elements.get(0);
    }

    static Element child(Element element, String name) {
        var elements = children(element, name);
        return single(elements);
    }

    static Element child(Document document, String name) {
        var elements = children(document, name);
        return single(elements);
    }

    static String attribute(Element element, String name) {
        return element.getAttribute(name);
    }

    static boolean hasAttribute(Element element, String name) {
        return element.hasAttribute(name);
    }
}
