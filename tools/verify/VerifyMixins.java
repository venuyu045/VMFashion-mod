import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 静态校验 mixin 目标：解析每个 mixin 的 @Mixin / @Inject，确认目标类里确实存在
 * 对应 name+descriptor 的方法（26.x 没有 remap，descriptor 必须完全一致）。
 */
public final class VerifyMixins {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path classesDir = Path.of(args[0]);
        Path mixinJson = Path.of(args[1]);
        String classpath = args[2];

        JsonObject root;
        try (var reader = Files.newBufferedReader(mixinJson, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String pkg = root.get("package").getAsString();
        JsonArray client = root.getAsJsonArray("client");

        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        urls.add(classesDir.toUri().toURL());

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            for (var element : client) {
                String simpleName = element.getAsString();
                Path classFile = classesDir.resolve(pkg.replace('.', '/')).resolve(simpleName + ".class");
                checkMixin(loader, classFile, pkg + "." + simpleName);
            }
        }

        System.out.println(failures == 0 ? "ALL MIXIN TARGETS RESOLVED" : (failures + " MIXIN TARGET(S) MISSING"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void checkMixin(ClassLoader loader, Path classFile, String mixinName) throws Exception {
        byte[] bytes = Files.readAllBytes(classFile);
        List<String> targets = new ArrayList<>();
        List<String> injects = new ArrayList<>();
        List<String> shadows = new ArrayList<>();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof Type type) {
                                    targets.add(type.getClassName());
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        if ("Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annDesc)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitArray(String n) {
                                    if (!"method".equals(n)) {
                                        return null;
                                    }
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String ignored, Object value) {
                                            injects.add(String.valueOf(value));
                                        }
                                    };
                                }
                            };
                        }
                        if ("Lorg/spongepowered/asm/mixin/Shadow;".equals(annDesc)) {
                            shadows.add(name + descriptor);
                            return null;
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);

        System.out.println("mixin " + mixinName + " -> " + targets);
        for (String target : targets) {
            Set<String> methods = methodsOf(loader, target);
            for (String inject : injects) {
                // "name(desc)ret" 或 "name"
                String needle = inject.contains("(") ? inject : null;
                String name = inject.contains("(") ? inject.substring(0, inject.indexOf('(')) : inject;
                boolean found;
                if (needle != null) {
                    found = methods.contains(needle);
                } else {
                    found = methods.stream().anyMatch(m -> m.startsWith(name + "("));
                }
                System.out.println((found ? "  [OK]   " : "  [FAIL] ") + "target method " + inject + " in " + target);
                if (!found) {
                    failures++;
                }
            }
            for (String shadow : shadows) {
                boolean found = methods.contains(shadow);
                System.out.println((found ? "  [OK]   " : "  [FAIL] ") + "shadow " + shadow + " in " + target);
                if (!found) {
                    failures++;
                }
            }
        }
    }

    private static Set<String> methodsOf(ClassLoader loader, String className) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new IllegalStateException("找不到目标类: " + className);
            }
            new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    result.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        }
        return result;
    }
}
