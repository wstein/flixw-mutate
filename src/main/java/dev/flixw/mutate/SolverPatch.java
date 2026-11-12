package dev.flixw.mutate;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Route (a) of the solver hook (spike #1): puts a call in front of a static method of a compiled class without
 * changing what the method does. The method {@code name} with the descriptor {@code (Z<ref>)<ref>} is renamed
 * {@code name$orig}; a new method with the old name and descriptor calls {@code hookClass.hookMethod(Object)}
 * with its second argument, then {@code name$orig} with both arguments, and returns its result. Callers of the
 * method, inside the class or in other classes, reach the new method.
 *
 * <p>This is a minimal class-file edit with no library: the plugin is built without the Flix compiler on its
 * class path, so the ASM that the compiler jar carries cannot be used at build time. The edit appends constant-pool
 * entries and one method and changes one name index. The new method has no branch, so it needs no stack map.
 */
final class SolverPatch {
    private SolverPatch() { }

    /** The name the original method gets. */
    static String renamed(String name) { return name + "$orig"; }

    /** Thrown if the class has no such method, or not with that shape. */
    static final class NotAsExpected extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        NotAsExpected(String message) { super(message); }
    }

    private record Member(int accessFlags, int nameIndex, int descIndex, int nameIndexOffset) {}

    /**
     * @param classFile  the class
     * @param method     the static method to wrap
     * @param descriptor its descriptor: a boolean, a reference, returning a reference
     * @param hookClass  internal name of the hook class ({@code dev/flixw/mutate/Splice})
     * @param hookMethod a public static method {@code (Ljava/lang/Object;)V} of the hook class
     */
    static byte[] wrap(byte[] classFile, String method, String descriptor, String hookClass, String hookMethod) {
        if (!descriptor.matches("\\(ZL[^;\\[]+;\\)L[^;\\[]+;")) throw new NotAsExpected("descriptor not supported: " + descriptor);
        try {
            return new Editor(classFile).wrap(method, descriptor, hookClass, hookMethod);
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new NotAsExpected("not a class file this edit can read: " + e);
        }
    }

    private static final class Editor {
        private final byte[] in;
        private final ByteBuffer b;
        private int cpCountOffset;
        private int cpEnd;
        private int cpCount;
        private final List<String> utf8 = new ArrayList<>(); // by constant-pool index, null for other entries

        Editor(byte[] in) {
            this.in = in;
            this.b = ByteBuffer.wrap(in);
        }

        byte[] wrap(String method, String descriptor, String hookClass, String hookMethod) {
            if (b.getInt() != 0xCAFEBABE) throw new NotAsExpected("not a class file");
            b.getShort();
            b.getShort();
            cpCountOffset = b.position();
            cpCount = u2();
            utf8.add(null);
            for (int i = 1; i < cpCount; i++) {
                int tag = b.get() & 0xff;
                String text = null;
                switch (tag) {
                    case 1 -> {
                        int len = u2();
                        text = new String(in, b.position(), len, StandardCharsets.UTF_8); // modified UTF-8; the names here are ASCII
                        b.position(b.position() + len);
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> b.position(b.position() + 4);
                    case 5, 6 -> {
                        b.position(b.position() + 8);
                        utf8.add(null); // a long or double takes two slots
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> b.position(b.position() + 2);
                    case 15 -> b.position(b.position() + 3);
                    default -> throw new NotAsExpected("constant pool tag " + tag);
                }
                utf8.add(text);
            }
            cpEnd = b.position();
            b.getShort(); // access
            int thisClass = u2();
            b.getShort(); // super
            int interfaces = u2();
            b.position(b.position() + 2 * interfaces);
            int fields = u2();
            for (int i = 0; i < fields; i++) member();
            int methodsCountOffset = b.position();
            int methods = u2();
            Member target = null;
            int methodsStart = b.position();
            for (int i = 0; i < methods; i++) {
                Member m = member();
                if (descIs(m, descriptor) && nameIs(m, method)) {
                    if (target != null) throw new NotAsExpected("two methods " + method + descriptor);
                    target = m;
                }
                if (nameIs(m, renamed(method))) throw new NotAsExpected("already wrapped: " + renamed(method));
            }
            int methodsEnd = b.position();
            if (target == null) throw new NotAsExpected("no method " + method + descriptor);
            if ((target.accessFlags() & 0x0008) == 0) throw new NotAsExpected(method + " is not static");
            return build(target, thisClass, methodsCountOffset, methodsStart, methodsEnd, method, hookClass, hookMethod);
        }

        private int u2() { return b.getShort() & 0xffff; }

        /** Reads a field or method entry. */
        private Member member() {
            int access = u2();
            int nameOffset = b.position();
            int name = u2();
            int desc = u2();
            int attrs = u2();
            for (int i = 0; i < attrs; i++) {
                b.getShort();
                int len = b.getInt();
                b.position(b.position() + len);
            }
            return new Member(access, name, desc, nameOffset);
        }

        private boolean nameIs(Member m, String s) { return s.equals(utf8.get(m.nameIndex())); }

        private boolean descIs(Member m, String s) { return s.equals(utf8.get(m.descIndex())); }

        private int utf8Index(String s) { return utf8.indexOf(s); }

        private byte[] build(Member target, int thisClass, int methodsCountOffset, int methodsStart, int methodsEnd, String method,
                             String hookClass, String hookMethod) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(in.length + 128);
                DataOutputStream out = new DataOutputStream(bos);
                int next = cpCount;
                int code = utf8Index("Code");
                List<byte[]> added = new ArrayList<>();
                if (code < 0) {
                    code = next++;
                    added.add(utf8Entry("Code"));
                }
                int origName = next++;
                added.add(utf8Entry(renamed(method)));
                int hookClassName = next++;
                added.add(utf8Entry(hookClass));
                int hookMethodName = next++;
                added.add(utf8Entry(hookMethod));
                int hookDesc = next++;
                added.add(utf8Entry("(Ljava/lang/Object;)V"));
                int hookClassRef = next++;
                added.add(refEntry(7, hookClassName, 0));
                int hookNameAndType = next++;
                added.add(refEntry(12, hookMethodName, hookDesc));
                int hookRef = next++;
                added.add(refEntry(10, hookClassRef, hookNameAndType));
                int origNameAndType = next++;
                added.add(refEntry(12, origName, target.descIndex()));
                int origRef = next++;
                added.add(refEntry(10, thisClass, origNameAndType));
                if (next > 0xffff) throw new NotAsExpected("constant pool full");

                out.write(in, 0, cpCountOffset);
                out.writeShort(next);
                out.write(in, cpCountOffset + 2, cpEnd - cpCountOffset - 2);
                for (byte[] e : added) out.write(e);
                out.write(in, cpEnd, methodsCountOffset - cpEnd);
                out.writeShort(((in[methodsCountOffset] & 0xff) << 8 | (in[methodsCountOffset + 1] & 0xff)) + 1);
                // the methods, with the name index of the target changed
                byte[] methods = new byte[methodsEnd - methodsStart];
                System.arraycopy(in, methodsStart, methods, 0, methods.length);
                int at = target.nameIndexOffset() - methodsStart;
                methods[at] = (byte) (origName >> 8);
                methods[at + 1] = (byte) origName;
                out.write(methods);
                // the new method: aload_1; invokestatic hook; iload_0; aload_1; invokestatic orig; areturn
                byte[] body = {
                    0x2b, (byte) 0xb8, (byte) (hookRef >> 8), (byte) hookRef,
                    0x1a, 0x2b, (byte) 0xb8, (byte) (origRef >> 8), (byte) origRef,
                    (byte) 0xb0};
                out.writeShort(target.accessFlags());
                out.writeShort(target.nameIndex());
                out.writeShort(target.descIndex());
                out.writeShort(1);
                out.writeShort(code);
                out.writeInt(12 + body.length);
                out.writeShort(2); // max stack
                out.writeShort(2); // max locals
                out.writeInt(body.length);
                out.write(body);
                out.writeShort(0); // exception table
                out.writeShort(0); // attributes
                out.write(in, methodsEnd, in.length - methodsEnd);
                return bos.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException(e); // a byte array does not fail
            }
        }

        private static byte[] utf8Entry(String s) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.writeByte(1);
            out.writeShort(bytes.length);
            out.write(bytes);
            return bos.toByteArray();
        }

        /** Class (7: one index) and the two-index entries (12 NameAndType, 10 Methodref). */
        private static byte[] refEntry(int tag, int a, int b) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(tag);
            out.writeShort(a);
            if (tag != 7) out.writeShort(b);
            return bos.toByteArray();
        }
    }
}
