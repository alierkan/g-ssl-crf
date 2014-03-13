package main.java.JuntoOutputConvert;

import main.java.Utility.Defaults;

import java.util.Hashtable;

/**
 * Copyright: Masoud Kiaeeha, Mohammad Aliannejadi
 * This work is licensed under the Creative Commons Attribution-NonCommercial 4.0
 * International License. To view a copy of this license, visit
 * http://creativecommons.org/licenses/by-nc/4.0/.
 */
public class JuntoOutputConvertor {
    private static String inputFileAddress, labelsFileAddress, outputFileAddress;

    public static void main(String[] args) {
        processArguments(args);

        JuntoOutputHandler outputHandler = new JuntoOutputHandler();
        outputHandler.convertJuntoOutputToViterbiFormat(inputFileAddress,
                labelsFileAddress, outputFileAddress);
    }

    private static void processArguments(String[] args) {
        Hashtable<String, String> config = new Hashtable<String, String>(10, (float) 0.9);
        for (int i=0; i<args.length ; ++i){
            if (args[i].startsWith("-")){
                if (i+1 < args.length){
                    config.put(getCommandFromArg(args[i]), args[++i]);
                }
            }
        }
        if (config.containsKey("h") || config.containsKey("help"))
            MessagePrinter.PrintAndDie("help->");

        inputFileAddress = Defaults.GetValueOrDie(config, "text");
        labelsFileAddress = Defaults.GetValueOrDie(config, "labels");
        outputFileAddress = Defaults.GetValueOrDie(config, "output");
    }

    /**
     * returns the command without the first character e.g. given "-command" output would be "command"
     * @param arg the command string
     * @return the command without the first "-" character
     */
    private static String getCommandFromArg(String arg) {
        return arg.substring(1, arg.length()).toLowerCase();
    }
}
