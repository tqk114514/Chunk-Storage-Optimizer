package tqk114514.chunkstorageoptimizer.compat.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks the compat's mixins against a release jar of the third-party mod, by reading bytecode
 * on both sides: the mixin's own class says exactly what it needs — its target, the members it
 * shadows, redirects and invokes — and every one of those is then looked up in the jar, along
 * its superclass chain. This runs against every release of the third-party mod for every
 * Minecraft this repo ships for (see the download tasks in the compat common build scripts),
 * so a release that moves any target fails the build here, not in a player's log.
 *
 * <p>Bytecode rather than decompiled source on purpose: it is the level Mixin itself works at,
 * so the contract this checks is exactly the contract the runtime honours — and it needs no
 * decompiler, whose output shifts with every version of the decompiler rather than of the mod.
 *
 * <p>The members this verifies are harvested from the mixin annotations, never listed by hand,
 * so the test cannot drift from what the mixins declare.
 */
public final class MixinContracts {

    /** One violated expectation, rendered for a failure message. */
    public record Violation(String description) {
    }

    /** A member the mixin needs: a method or a field on one of the third party's classes. */
    private record Member(String owner, String name, String descriptor, boolean isMethod) {
    }

    private final List<ClassNode> mixins = new ArrayList<>();
    /** Owner package prefixes that mark a reference as belonging to the third-party mod. */
    private final Set<String> targetPrefixes = new HashSet<>();
    private final List<Member> members = new ArrayList<>();
    /** Method names the injectors apply inside; the injection is dead without them. */
    private final List<Member> hostMethods = new ArrayList<>();
    /** pointcut targets, and the host method they must be reachable in. */
    private final List<CallSite> callSites = new ArrayList<>();
    /** mixin field name -> the host method whose LDC constants must contain its value. */
    private final Map<String, String> identityLiterals = new LinkedHashMap<>();

    private record CallSite(Member target, String hostMethod) {
    }

    private MixinContracts() {
    }

    /**
     * Builds the contract from the mixin classes' own bytecode. The classes are read from the
     * test classpath — the compiled output of the compat's common half — so the contract is
     * always the one the next build will actually ship.
     *
     * @param mixinClassNames internal names of the mixin classes, e.g.
     *                         {@code tqk114514/.../MapSaveLoadMixin}
     * @param identityLiterals mixin static field -> third-party method: the field's string
     *                         value must appear as a constant in that method's bytecode. This
     *                         is for mixins that match a third-party constant by string
     *                         identity — the match silently stops working when the constant
     *                         changes shape, and only this check notices.
     */
    public static MixinContracts from(String[] mixinClassNames, Map<String, String> identityLiterals) {
        MixinContracts contract = new MixinContracts();
        for (String name : mixinClassNames) {
            ClassNode node = readClass(MixinContracts.class.getClassLoader(), name);
            contract.mixins.add(node);
            contract.harvest(node);
        }
        contract.identityLiterals.putAll(identityLiterals);
        return contract;
    }

    /** Verifies the contract against one release jar of the third-party mod. */
    public List<Violation> verifyAgainstJar(Path jar) {
        jarClassCache.clear();
        try (JarFile file = new JarFile(jar.toFile())) {
            Map<String, byte[]> entries = new HashMap<>();
            Enumeration<JarEntry> all = file.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        // Keyed by class name — the ".class" suffix is part of the entry name,
                        // not of the name things get looked up by.
                        String className = entry.getName().replace('/', '.');
                        entries.put(className.substring(0, className.length() - ".class".length()),
                            in.readAllBytes());
                    }
                }
            }
            List<Violation> violations = new ArrayList<>();
            for (Member member : members) {
                if (!exists(entries, member)) {
                    violations.add(new Violation("missing " + (member.isMethod() ? "method" : "field")
                        + " " + member.name + member.descriptor + " on " + member.owner));
                }
            }
            for (Member host : hostMethods) {
                if (!exists(entries, host)) {
                    violations.add(new Violation("missing host method " + host.name
                        + (host.descriptor.isEmpty() ? "" : host.descriptor) + " on " + host.owner
                        + " — every injector in it is dead"));
                }
            }
            for (CallSite site : callSites) {
                MethodNode host = findMethod(entries, new Member(hostOwner(), site.hostMethod(), "", true));
                if (host == null) {
                    continue; // the host check above already reported it
                }
                if (!containsCall(host, site.target())) {
                    violations.add(new Violation("no call to " + site.target().name + site.target().descriptor
                        + " inside " + site.hostMethod() + " — the redirect has nothing to apply to"));
                }
            }
            for (Map.Entry<String, String> literal : identityLiterals.entrySet()) {
                String value = mixinConstant(literal.getKey());
                String host = literal.getValue();
                MethodNode method = findMethod(entries, new Member(hostOwner(), host, "", true));
                if (method == null) {
                    continue; // reported above
                }
                boolean present = false;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode ldc && value.equals(ldc.cst)) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    violations.add(new Violation("the string " + value + " (this compat matches it by "
                        + "identity, pinned in the mixin's " + literal.getKey()
                        + ") is no longer a constant in " + host + " — the support silently stops working"));
                }
            }
            return violations;
        } catch (IOException e) {
            return List.of(new Violation("cannot read " + jar + ": " + e));
        }
    }

    // ------------------------------------------------------------------ harvesting the mixin

    private void harvest(ClassNode mixin) {
        AnnotationNode mixinAnnotation = annotation(mixin.visibleAnnotations, "Mixin");
        AnnotationNode hiddenMixinAnnotation = annotation(mixin.invisibleAnnotations, "Mixin");
        AnnotationNode mix = mixinAnnotation != null ? mixinAnnotation : hiddenMixinAnnotation;
        if (mix == null) {
            throw new IllegalStateException(mixin.name + " carries no @Mixin annotation");
        }
        List<String> targets = annotationValues(mix, "value");
        for (String target : targets) {
            members.add(new Member(internalName(target), "", "", false));
        }
        // Two passes: the contract's package prefix is the longest one shared by every class
        // the mixins touch, so a helper outside the target's own package (Xaero's MapRegion
        // under xaero/map/region, say) is inside the contract too.
        Set<String> ownerRoots = new HashSet<>();
        for (String target : targets) {
            ownerRoots.add(internalName(target));
        }
        for (MethodNode method : mixin.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                String owner = null;
                if (insn instanceof MethodInsnNode call) {
                    owner = call.owner;
                } else if (insn instanceof FieldInsnNode field) {
                    owner = field.owner;
                }
                if (owner != null && !owner.startsWith("java/") && !owner.startsWith("org/")
                    && !owner.startsWith("jdk/") && !owner.startsWith("tqk114514/")) {
                    ownerRoots.add(owner);
                }
            }
        }
        targetPrefixes.add(commonPackagePrefix(ownerRoots));
        for (MethodNode method : mixin.methods) {
            harvestMethod(mixin, method);
        }
        for (FieldNode field : mixin.fields) {
            if (annotation(field.visibleAnnotations, "Shadow") != null
                || annotation(field.invisibleAnnotations, "Shadow") != null) {
                for (String target : targets) {
                    members.add(new Member(internalName(target), field.name, field.desc, false));
                }
            }
        }
    }

    private void harvestMethod(ClassNode mixin, MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            annotations.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            annotations.addAll(method.invisibleAnnotations);
        }
        boolean shadow = annotations.stream().anyMatch(a -> a.desc.endsWith("Shadow;"));
        if (shadow) {
            members.add(new Member(targetOf(mixin), method.name, method.desc, true));
        }
        for (AnnotationNode annotation : annotations) {
            String type = annotation.desc;
            if (type.endsWith("Redirect;") || type.endsWith("WrapOperation;")
                || type.endsWith("ModifyArg;") || type.endsWith("ModifyArgs;")
                || type.endsWith("ModifyVariable;") || type.endsWith("ModifyConstant;")) {
                for (String host : annotationValues(annotation, "method")) {
                    hostMethods.add(new Member(targetOf(mixin), host, "", true));
                    addPointcut(annotation, host);
                }
            } else if (type.endsWith("Inject;") || type.endsWith("ModifyExpressionValue;")) {
                for (String host : annotationValues(annotation, "method")) {
                    hostMethods.add(new Member(targetOf(mixin), host, "", true));
                }
            } else if (type.endsWith("Invoker;")) {
                for (String value : annotationValues(annotation, "value")) {
                    members.add(new Member(targetOf(mixin), value, method.desc, true));
                }
            }
        }
        // Every third-party member the handler bodies reach for — collected from the bytecode
        // so the list can never drift behind the code.
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && isTargetClass(call.owner)) {
                members.add(new Member(call.owner, call.name, call.desc, true));
            } else if (insn instanceof FieldInsnNode field && isTargetClass(field.owner)) {
                members.add(new Member(field.owner, field.name, field.desc, false));
            }
        }
    }

    private void addPointcut(AnnotationNode annotation, String host) {
        AnnotationNode at = annotationValue(annotation, "at");
        if (at == null) {
            return;
        }
        Object target = annotationValue(at, "target");
        if (!(target instanceof String descriptor) || !descriptor.contains(";")) {
            return;
        }
        Member parsed = parseTargetDescriptor(descriptor);
        if (parsed != null && isTargetClass(parsed.owner())) {
            callSites.add(new CallSite(parsed, host));
        }
    }

    /** {@code Lowner;name(descriptor)returnType} — the form every injection point names. */
    private Member parseTargetDescriptor(String target) {
        int ownerEnd = target.indexOf(';');
        if (ownerEnd < 0) {
            return null;
        }
        String owner = target.substring(1, ownerEnd);
        int nameEnd = target.indexOf('(', ownerEnd);
        if (nameEnd < 0) {
            return new Member(owner, target.substring(ownerEnd + 1), "", true);
        }
        return new Member(owner, target.substring(ownerEnd + 1, nameEnd),
            target.substring(nameEnd), true);
    }

    private String targetOf(ClassNode mixin) {
        AnnotationNode mix = annotation(mixin.visibleAnnotations, "Mixin");
        if (mix == null) {
            mix = annotation(mixin.invisibleAnnotations, "Mixin");
        }
        List<String> values = annotationValues(mix, "value");
        if (values.size() != 1) {
            throw new IllegalStateException(mixin.name + " must have exactly one @Mixin target for this check");
        }
        return internalName(values.get(0));
    }

    private String hostOwner() {
        return targetOf(mixins.get(0));
    }

    /** The constant a mixin field pins, read from the compiled class's constant pool. */
    private String mixinConstant(String fieldName) {
        for (ClassNode mixin : mixins) {
            for (FieldNode field : mixin.fields) {
                if (field.name.equals(fieldName) && field.value instanceof String value) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("no static String constant named " + fieldName + " in the mixins");
    }

    // ------------------------------------------------------------------ looking things up

    private boolean exists(Map<String, byte[]> jar, Member member) {
        if (member.name().isEmpty()) {
            return jar.containsKey(member.owner().replace('/', '.'));
        }
        if (member.isMethod()) {
            return findMethod(jar, member) != null;
        }
        String chain = member.owner();
        while (chain != null) {
            ClassNode node = readJarClass(jar, chain);
            if (node == null) {
                return false;
            }
            for (FieldNode field : node.fields) {
                if (field.name.equals(member.name()) && field.desc.equals(member.descriptor())) {
                    return true;
                }
            }
            chain = node.superName;
        }
        return false;
    }

    private MethodNode findMethod(Map<String, byte[]> jar, Member member) {
        String chain = member.owner();
        Set<String> seen = new HashSet<>();
        while (chain != null && seen.add(chain)) {
            ClassNode node = readJarClass(jar, chain);
            if (node == null) {
                return null;
            }
            for (MethodNode method : node.methods) {
                boolean descriptorMatches = member.descriptor().isEmpty() || method.desc.equals(member.descriptor());
                if (method.name.equals(member.name()) && descriptorMatches) {
                    return method;
                }
            }
            for (String iface : node.interfaces) {
                MethodNode inherited = findMethod(jar, new Member(iface, member.name(), member.descriptor(), true));
                if (inherited != null) {
                    return inherited;
                }
            }
            chain = node.superName;
        }
        return null;
    }

    private static boolean containsCall(MethodNode host, Member target) {
        for (AbstractInsnNode insn : host.instructions) {
            if (insn instanceof MethodInsnNode call
                && call.owner.equals(target.owner()) && call.name.equals(target.name())
                && call.desc.equals(target.descriptor())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTargetClass(String owner) {
        return targetPrefixes.stream().anyMatch(owner::startsWith);
    }

    /** The longest package prefix every one of these classes shares, trailing slash included. */
    private static String commonPackagePrefix(Set<String> owners) {
        String prefix = null;
        for (String owner : owners) {
            String packageName = owner.substring(0, owner.lastIndexOf('/') + 1);
            prefix = prefix == null ? packageName : commonPrefix(prefix, packageName);
        }
        return prefix;
    }

    private static String commonPrefix(String a, String b) {
        int length = Math.min(a.length(), b.length());
        int i = 0;
        while (i < length && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int lastSlash = Math.min(i - 1, Math.max(a.lastIndexOf('/'), b.lastIndexOf('/')));
        return a.substring(0, lastSlash + 1);
    }

    // ------------------------------------------------------------------ small asm helpers

    private static ClassNode readClass(ClassLoader loader, String internalName) {
        // getResource speaks class paths, so the slashed internal name it is — a dotted one
        // looks for a file literally named "a.b.C.class" and finds nothing.
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                throw new IllegalStateException("mixin class not on the test classpath: " + internalName);
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read mixin class " + internalName, e);
        }
    }

    private final Map<String, ClassNode> jarClassCache = new HashMap<>();

    private ClassNode readJarClass(Map<String, byte[]> jar, String internalName) {
        if (jarClassCache.containsKey(internalName)) {
            return jarClassCache.get(internalName);
        }
        ClassNode node = null;
        byte[] bytes = jar.get(internalName.replace('/', '.'));
        if (bytes != null) {
            node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
        }
        jarClassCache.put(internalName, node);
        return node;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String simpleName) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.endsWith(simpleName + ";")) {
                return annotation;
            }
        }
        return null;
    }

    private static AnnotationNode annotationValue(AnnotationNode annotation, String name) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                Object value = annotation.values.get(i + 1);
                if (value instanceof AnnotationNode nested) {
                    return nested;
                }
                if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof AnnotationNode) {
                    return (AnnotationNode) list.get(0);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> annotationValues(AnnotationNode annotation, String name) {
        if (annotation == null || annotation.values == null) {
            return List.of();
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                Object value = annotation.values.get(i + 1);
                if (value instanceof List<?> list) {
                    return ((List<Object>) list).stream()
                        .map(MixinContracts::annotationString)
                        .collect(Collectors.toList());
                }
                return List.of(annotationString(value));
            }
        }
        return List.of();
    }

    private static String annotationString(Object value) {
        if (value instanceof String string) {
            return string;
        }
        // A class-literal annotation value is laid out as "Linternal/name;" (plus the label array).
        String string = value.toString();
        return string;
    }

    private static String internalName(String annotationValue) {
        String value = annotationValue;
        if (value.startsWith("L") && value.endsWith(";")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
