package org.openjdk.skara.args;

public interface CommandCtor<T extends Command> {
    T construct(String name, String helpText, Main main);
}
