package org.openjdk.skara.args;

public class CommandMain<T extends Command> {
    private final CommandCtor<T> ctor;
    private final String name;
    private final String helpText;

    CommandMain(CommandCtor<T> ctor, String name, String helpText) {
        this.ctor = ctor;
        this.name = name;
        this.helpText = helpText;
    }

    public T main(Main main) {
        return ctor.construct(name, helpText, main);
    }
}
