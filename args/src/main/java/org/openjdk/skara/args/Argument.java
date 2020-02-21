/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.args;

import java.util.NoSuchElementException;
import java.util.function.*;

public class Argument {
    private final String value;

    public Argument() {
        this.value = null;
    }

    public Argument(String value) {
        this.value = value;
    }

    public boolean isPresent() {
        return value != null;
    }

    public <T> T via(Function<String, ? extends T> f) {
        if (!isPresent()) {
            throw new NoSuchElementException();
        }

        return f.apply(value);
    }

    public int asInt() {
        return via(Integer::parseInt);
    }

    public double asDouble() {
        return via(Double::parseDouble);
    }

    public float asFloat() {
        return via(Float::parseFloat);
    }

    public boolean  asBoolean() {
        return via(Boolean::parseBoolean);
    }

    public String asString() {
        return value == null ? null : via(Function.identity());
    }

    public Argument or(int value) {
        return isPresent() ? this : new Argument(Integer.toString(value));
    }

    public Argument or(double value) {
        return isPresent() ? this : new Argument(Double.toString(value));
    }

    public Argument or(long value) {
        return isPresent() ? this : new Argument(Long.toString(value));
    }

    public Argument or(boolean value) {
        return isPresent() ? this : new Argument(Boolean.toString(value));
    }

    public Argument or(float value) {
        return isPresent() ? this : new Argument(Float.toString(value));
    }

    public Argument or(String value) {
        return isPresent() ? this : new Argument(value);
    }

    public Argument or(Argument other) {
        return isPresent() ? this : other;
    }

    public Argument or(Supplier<String> supplier) {
        return isPresent() ? this : new Argument(supplier.get());
    }

    public int orInt(int value) {
        return orInt(() -> value);
    }

    public int orInt(Supplier<Integer> supplier) {
        return isPresent() ? asInt() : supplier.get().intValue();
    }

    public double orDouble(double value) {
        return orDouble(() -> value);
    }

    public double orDouble(Supplier<Double> supplier) {
        return isPresent() ? asDouble() : supplier.get().doubleValue();
    }

    public float orFloat(float value) {
        return orFloat(() -> value);
    }

    public float orFloat(Supplier<Float> supplier) {
        return isPresent() ? asFloat() : supplier.get().floatValue();
    }

    public boolean orBoolean(boolean value) {
        return orBoolean(() -> value);
    }

    public boolean orBoolean(Supplier<Boolean> supplier) {
        return isPresent() ? asBoolean() : supplier.get().booleanValue();
    }

    public String orString(String value) {
        return orString(() -> value);
    }

    public String orString(Supplier<String> supplier) {
        return isPresent() ? asString() : supplier.get();
    }
}
