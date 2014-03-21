package com.cloud.network.utils;

/**
 * User: hkp
 * Date: Oct 28, 2013
 * Time: 12:51:43 PM
 */
public class Utils {
    public static final String TIME_FORMAT = "HH:mm:ss";
    public static String NEW_LINE = "\n";

    public static String toMinSec(long timeInMilliSec) {
        long min = timeInMilliSec /(1000 * 60);
        long sec = (timeInMilliSec - (min * 1000 * 60))/1000;
        return min + " min and " + sec + " secs";
    }

    /**
     * Pattern will contain place-holders for arguments in the form of %1, %2 ..
     * Replace them sequentially by tokens specified in arguments
     *
     * @param pattern
     * @param arguments
     * @return
     */
    public static String patternReplace(String pattern, String ... arguments) {
        int i=1;

        StringBuilder builder = new StringBuilder(pattern);

        for (String argument : arguments) {
            String indexToken = "%" + i;
            int index = builder.indexOf(indexToken);
            if (index != -1) {
                builder.replace(index, index + indexToken.length(),argument);
            }
            ++i;
        }
        return builder.toString();
    }
}
