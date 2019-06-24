/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.census;

import java.nio.file.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import javax.xml.parsers.*;

class XML {
    static Document parse(Path p) throws IOException {
        return parse(new InputSource(Files.newInputStream(p)));
    }

    static Document parse(List<String> lines) throws IOException {
        return parse(new InputSource(new StringReader(String.join("\n", lines))));
    }

    private static Document parse(InputSource source) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            return builder.parse(source);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        } catch (SAXException e) {
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
