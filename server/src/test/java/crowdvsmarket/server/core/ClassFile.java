package crowdvsmarket.server.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The part of a Java class file {@code EngineIoRuleTest} needs, read without a library (no ASM, no class-file API): the class names it
 * refers to, and for every method the methods it calls ({@code invoke*}) and the lambdas / method references it creates
 * ({@code invokedynamic} with a lambda metafactory). Only the constant pool, the method table with its {@code Code} attributes and
 * {@code BootstrapMethods} are read; every other attribute is skipped by its length.
 */
final class ClassFile {

    /** A method called by an {@code invoke*} instruction: the class named at the call site, the name and the descriptor. */
    record Call(String owner, String name, String desc) {
        String key() {
            return owner + "." + name + desc;
        }
    }

    /** A lambda or method reference created by a method: the functional interface's type (return type of the call site) and the
     *  method that is its body. */
    record Lambda(String interfaceType, Call body) {
    }

    static final class Method {
        final String name;
        final String desc;
        final int access;
        final List<Call> calls = new ArrayList<>();
        final List<Lambda> lambdas = new ArrayList<>();

        Method(String name, String desc, int access) {
            this.name = name;
            this.desc = desc;
            this.access = access;
        }
    }

    String name;
    String superName;
    final List<String> interfaces = new ArrayList<>();
    int access;
    /** Every class named in the constant pool (internal names), including the types of fields and calls' owners. */
    final Set<String> classRefs = new HashSet<>();
    /** Whether the class carries {@code @RegisterRestClient} (its name is in the constant pool). */
    boolean restClient;
    final List<Method> methods = new ArrayList<>();

    boolean isInterface() {
        return (access & 0x0200) != 0;
    }

    // ---------------------------------------------------------------------------------------

    static ClassFile parse(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != 0xCAFEBABE) {
            throw new IllegalArgumentException("not a class file");
        }
        b.getShort();
        b.getShort();
        int n = u2(b);
        int[] tag = new int[n];
        int[] a = new int[n];
        int[] c = new int[n];
        String[] utf = new String[n];
        for (int i = 1; i < n; i++) {
            tag[i] = b.get() & 0xFF;
            switch (tag[i]) {
                case 1 -> {
                    byte[] s = new byte[u2(b)];
                    b.get(s);
                    utf[i] = new String(s, StandardCharsets.UTF_8);
                }
                case 3, 4 -> b.getInt();
                case 5, 6 -> {
                    b.getLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> a[i] = u2(b);
                case 9, 10, 11, 12, 17, 18 -> {
                    a[i] = u2(b);
                    c[i] = u2(b);
                }
                case 15 -> {
                    a[i] = b.get() & 0xFF;
                    c[i] = u2(b);
                }
                default -> throw new IllegalArgumentException("constant pool tag " + tag[i]);
            }
        }
        ClassFile cf = new ClassFile();
        for (int i = 1; i < n; i++) {
            if (tag[i] == 7) {
                cf.classRefs.add(utf[a[i]]);
            }
            if (tag[i] == 1 && utf[i].contains("RegisterRestClient")) {
                cf.restClient = true;
            }
        }
        cf.access = u2(b);
        cf.name = utf[a[u2(b)]];
        int sup = u2(b);
        cf.superName = sup == 0 ? null : utf[a[sup]];
        int ic = u2(b);
        for (int i = 0; i < ic; i++) {
            cf.interfaces.add(utf[a[u2(b)]]);
        }
        int fields = u2(b);
        for (int i = 0; i < fields; i++) {
            b.position(b.position() + 6);
            skipAttributes(b);
        }
        int methods = u2(b);
        // the call sites of invokedynamic are resolved after the BootstrapMethods attribute (at the end of the class) is read
        List<Object[]> pending = new ArrayList<>(); // {Method, Integer cp index of the indy}
        for (int i = 0; i < methods; i++) {
            int acc = u2(b);
            String name = utf[u2(b)];
            String desc = utf[u2(b)];
            Method m = new Method(name, desc, acc);
            cf.methods.add(m);
            int attrs = u2(b);
            for (int k = 0; k < attrs; k++) {
                String an = utf[u2(b)];
                int len = b.getInt();
                int end = b.position() + len;
                if (an.equals("Code")) {
                    b.getShort();
                    b.getShort();
                    int codeLen = b.getInt();
                    scan(b, b.position(), codeLen, m, tag, a, c, utf, pending);
                }
                b.position(end);
            }
        }
        List<int[]> bootstrap = new ArrayList<>();
        int classAttrs = u2(b);
        for (int k = 0; k < classAttrs; k++) {
            String an = utf[u2(b)];
            int len = b.getInt();
            int end = b.position() + len;
            if (an.equals("BootstrapMethods")) {
                int count = u2(b);
                for (int i = 0; i < count; i++) {
                    int ref = u2(b);
                    int args = u2(b);
                    int[] entry = new int[1 + args];
                    entry[0] = ref;
                    for (int j = 0; j < args; j++) {
                        entry[1 + j] = u2(b);
                    }
                    bootstrap.add(entry);
                }
            }
            b.position(end);
        }
        for (Object[] p : pending) {
            Method m = (Method) p[0];
            int indy = (Integer) p[1];
            int[] bsm = bootstrap.get(a[indy]);
            // only the lambda metafactories: arg 1 is the method handle of the body
            Call bootstrapCall = handleCall(bsm[0], tag, a, c, utf);
            if (bootstrapCall == null || !bootstrapCall.owner().equals("java/lang/invoke/LambdaMetafactory") || bsm.length < 3) {
                continue;
            }
            Call body = handleCall(bsm[2], tag, a, c, utf);
            String desc = utf[c[c[indy]]];
            String iface = desc.substring(desc.indexOf(')') + 1);
            if (body != null) {
                m.lambdas.add(new Lambda(iface, body));
            }
        }
        return cf;
    }

    /** The call a constant-pool MethodHandle stands for, or null for anything else. */
    private static Call handleCall(int idx, int[] tag, int[] a, int[] c, String[] utf) {
        if (tag[idx] != 15) {
            return null;
        }
        return methodref(c[idx], tag, a, c, utf);
    }

    private static Call methodref(int idx, int[] tag, int[] a, int[] c, String[] utf) {
        int nat = c[idx];
        return new Call(utf[a[a[idx]]], utf[a[nat]], utf[c[nat]]);
    }

    private static void scan(ByteBuffer b, int start, int len, Method m, int[] tag, int[] a, int[] c, String[] utf,
                             List<Object[]> pending) {
        int pc = 0;
        while (pc < len) {
            int op = b.get(start + pc) & 0xFF;
            if (op >= 182 && op <= 184 || op == 185) {
                int idx = b.getShort(start + pc + 1) & 0xFFFF;
                m.calls.add(methodref(idx, tag, a, c, utf));
            } else if (op == 186) {
                pending.add(new Object[] {m, b.getShort(start + pc + 1) & 0xFFFF});
            }
            pc += length(b, start, pc, op);
        }
    }

    private static int length(ByteBuffer b, int start, int pc, int op) {
        if (op == 170) { // tableswitch
            int p = start + pc + 1 + (3 - pc % 4);
            int low = b.getInt(p + 4);
            int high = b.getInt(p + 8);
            return p + 12 + (high - low + 1) * 4 - (start + pc);
        }
        if (op == 171) { // lookupswitch
            int p = start + pc + 1 + (3 - pc % 4);
            int pairs = b.getInt(p + 4);
            return p + 8 + pairs * 8 - (start + pc);
        }
        if (op == 196) { // wide
            return (b.get(start + pc + 1) & 0xFF) == 132 ? 6 : 4;
        }
        if (op == 16 || op == 18 || op >= 21 && op <= 25 || op >= 54 && op <= 58 || op == 169 || op == 188) {
            return 2;
        }
        if (op == 17 || op == 19 || op == 20 || op == 132 || op >= 153 && op <= 168 || op >= 178 && op <= 184 || op == 187
                || op == 189 || op == 192 || op == 193 || op == 198 || op == 199) {
            return 3;
        }
        if (op == 185 || op == 186 || op == 200 || op == 201) {
            return 5;
        }
        if (op == 197) {
            return 4;
        }
        return 1;
    }

    private static void skipAttributes(ByteBuffer b) {
        int attrs = u2(b);
        for (int k = 0; k < attrs; k++) {
            b.getShort();
            int len = b.getInt();
            b.position(b.position() + len);
        }
    }

    private static int u2(ByteBuffer b) {
        return b.getShort() & 0xFFFF;
    }
}
