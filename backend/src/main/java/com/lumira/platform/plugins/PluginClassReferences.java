package com.lumira.platform.plugins;

import java.io.*;
import java.util.*;

/** Resolve symbolic class references as well as public signatures, without executing code. */
final class PluginClassReferences {
    static Set<String> read(InputStream stream) throws IOException {
        var in = new DataInputStream(stream);
        if (in.readInt() != 0xcafebabe) throw new IllegalArgumentException("invalidPluginPackage");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        var utf = new String[count];
        var classes = new ArrayList<Integer>();
        var descriptors = new ArrayList<Integer>();
        for (int i = 1; i < count; i++)
            switch (in.readUnsignedByte()) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7 -> classes.add(in.readUnsignedShort());
                case 8, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 17, 18 -> in.skipNBytes(4);
                case 12 -> {
                    in.readUnsignedShort();
                    descriptors.add(in.readUnsignedShort());
                }
                case 16 -> descriptors.add(in.readUnsignedShort());
                case 15 -> in.skipNBytes(3);
                default -> throw new IllegalArgumentException("invalidPluginPackage");
            }
        var result = new HashSet<String>();
        for (int index : classes) result.add(utf[index].replace('/', '.'));
        var pattern = java.util.regex.Pattern.compile("L([^;]+);");
        for (int index : descriptors) {
            var matcher = pattern.matcher(utf[index]);
            while (matcher.find()) result.add(matcher.group(1).replace('/', '.'));
        }
        return result;
    }
}
