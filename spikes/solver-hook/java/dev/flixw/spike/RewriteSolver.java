package dev.flixw.spike;

import ca.uwaterloo.flix.language.ast.BytecodeAst;
import ca.uwaterloo.flix.language.phase.jvm.JvmClass;
import ca.uwaterloo.flix.runtime.CompilationResult;
import java.lang.constant.ClassDesc;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import scala.collection.immutable.Map;

/**
 * Route (a), build side: replaces the bytes of {@code Fixpoint3.Solver.Def$runSolverInternal} in a
 * compiled program. The original {@code staticApply(ZLTagged$;)Ldev/flix/runtime/Result$;} is renamed to
 * {@code staticApply$orig}; a new {@code staticApply} with the old descriptor calls {@link Splice}.
 * ASM is the copy inside the stock flix.jar (org.objectweb.asm).
 */
public final class RewriteSolver {
    private RewriteSolver() {}

    /** The hook target, as the compatibility contract names it. */
    public static final String TARGET_CLASS = "Fixpoint3/Solver/Def$runSolverInternal";
    public static final String TARGET_METHOD = "staticApply";
    public static final String TARGET_DESC = "(ZLTagged$;)Ldev/flix/runtime/Result$;";
    static final String ORIG = "staticApply$orig";

    /** Returns a new compilation result whose solver class is rewritten. Throws if the target is not as expected. */
    public static CompilationResult rewrite(CompilationResult cr) {
        BytecodeAst.Root root = cr.root();
        Map<ClassDesc, JvmClass> classes = root.classes();
        ClassDesc target = ClassDesc.of("Fixpoint3.Solver.Def$runSolverInternal");
        scala.Option<JvmClass> found = classes.get(target);
        if (found.isEmpty()) throw new IllegalStateException("hook target class not found: " + TARGET_CLASS);
        byte[] patched = rewrite(found.get().bytecode());
        Map<ClassDesc, JvmClass> newClasses = classes.updated(target, new JvmClass(target, patched));
        BytecodeAst.Root newRoot = root.copy(newClasses, root.tests(), root.main(), root.sources());
        return new CompilationResult(newRoot, cr.totalTime(), cr.codeSize(), cr.flix());
    }

    public static byte[] rewrite(byte[] in) {
        ClassReader reader = new ClassReader(in);
        ClassWriter writer = new ClassWriter(0);
        boolean[] seen = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (name.equals(TARGET_METHOD) && desc.equals(TARGET_DESC) && (access & Opcodes.ACC_STATIC) != 0) {
                    seen[0] = true;
                    return super.visitMethod(access, ORIG, desc, sig, ex);
                }
                return super.visitMethod(access, name, desc, sig, ex);
            }

            @Override
            public void visitEnd() {
                if (!seen[0]) throw new IllegalStateException("hook target method not found: " + TARGET_METHOD + TARGET_DESC);
                String self = TARGET_CLASS;
                String splice = "dev/flixw/spike/Splice";
                String obj = "(Ljava/lang/Object;)Ljava/lang/Object;";
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        TARGET_METHOD, TARGET_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, splice, "before", obj, false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "Tagged$");
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, self, ORIG, TARGET_DESC, false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, splice, "after", obj, false);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "dev/flix/runtime/Result$");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }
}
