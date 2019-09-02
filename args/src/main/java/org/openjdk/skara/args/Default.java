package org.openjdk.skara.args;

public class Default extends Command {
    Default(String name, String helpText, Main main) {
        super(name, helpText, main);
    }

    public static CommandHelpText<Default> name(String name) {
        return new CommandHelpText<>(Default::new, name);
    }
}
