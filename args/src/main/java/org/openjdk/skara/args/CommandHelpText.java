package org.openjdk.skara.args;

public class CommandHelpText<T extends Command> {
    private final CommandCtor<T> ctor;
    private final String name;

    CommandHelpText(CommandCtor<T> ctor, String name) {
        this.ctor = ctor;
        this.name = name;
    }

    public CommandMain<T> helptext(String helpText) {
        return new CommandMain<>(ctor, name, helpText);
    }
}
