package com.replaymod.gradle.remap;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiQualifiedNamedElement;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiTypeElement;
import org.cadixdev.bombe.type.signature.MethodSignature;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.model.ClassMapping;
import org.cadixdev.lorenz.model.Mapping;
import org.cadixdev.lorenz.model.MethodMapping;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static com.replaymod.gradle.remap.PsiUtils.getSignature;

class PsiMapper {
    private static final String CLASS_MIXIN = "org.spongepowered.asm.mixin.Mixin";
    private static final String CLASS_ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor";
    private static final String CLASS_AT = "org.spongepowered.asm.mixin.injection.At";
    private static final String CLASS_INJECT = "org.spongepowered.asm.mixin.injection.Inject";
    private static final String CLASS_REDIRECT = "org.spongepowered.asm.mixin.injection.Redirect";

    private final MappingSet map;
    private final Map<String, ClassMapping<?, ?>> mixinMappings = new HashMap<>();
    private final PsiFile file;
    private boolean error;
    private TreeMap<TextRange, String> changes = new TreeMap<>(Comparator.comparing(TextRange::getStartOffset));

    PsiMapper(MappingSet map, PsiFile file) {
        this.map = map;
        this.file = file;
    }

    private void error(PsiElement at, String message) {
        int line = StringUtil.offsetToLineNumber(file.getText(), at.getTextOffset());
        System.err.println(file.getName() + ":" + line + ": " + message);
        error = true;
    }

    private void replace(PsiElement e, String with) {
        changes.put(e.getTextRange(), with);
    }

    private void replaceIdentifier(PsiElement parent, String with) {
        for (PsiElement child : parent.getChildren()) {
            if (child instanceof PsiIdentifier) {
                replace(child, with);
                return;
            }
        }
    }

    private boolean valid(PsiElement e) {
        TextRange range = e.getTextRange();
        TextRange before = changes.ceilingKey(range);
        return before == null || !before.intersects(range);
    }

    private String getResult(String text) {
        if (error) {
            return null;
        }
        for (Map.Entry<TextRange, String> change : changes.descendingMap().entrySet()) {
            text = change.getKey().replace(text, change.getValue());
        }
        return text;
    }

    private static boolean isSwitchCase(PsiElement e) {
        if (e instanceof PsiSwitchLabelStatement) {
            return true;
        }
        PsiElement parent = e.getParent();
        return parent != null && isSwitchCase(parent);
    }

    private void map(PsiElement expr, PsiField field) {
        PsiClass declaringClass = field.getContainingClass();
        if (declaringClass == null) return;
        String name = declaringClass.getQualifiedName();
        if (name == null) return;
        ClassMapping<?, ?> mapping = this.mixinMappings.get(name);
        if (mapping == null) {
            mapping = map.getClassMapping(name).orElse(null);
        }
        if (mapping == null) return;
        String mapped = mapping.getFieldMapping(field.getName()).map(Mapping::getDeobfuscatedName).orElse(null);
        if (mapped == null || mapped.equals(field.getName())) return;
        replaceIdentifier(expr, mapped);

        if (expr instanceof PsiJavaCodeReferenceElement
                && !((PsiJavaCodeReferenceElement) expr).isQualified() // qualified access is fine
                && !isSwitchCase(expr) // referencing constants in case statements is fine
        ) {
            error(expr, "Implicit member reference to remapped field \"" + field.getName() + "\". " +
                    "This can cause issues if the remapped reference becomes shadowed by a local variable and is therefore forbidden. " +
                    "Use \"this." + field.getName() + "\" instead.");
        }
    }

    private void map(PsiElement expr, PsiMethod method) {
        if (method.isConstructor()) return;

        PsiClass declaringClass = method.getContainingClass();
        if (declaringClass == null) return;
        ArrayDeque<PsiClass> parentQueue = new ArrayDeque<>();
        parentQueue.offer(declaringClass);
        ClassMapping<?, ?> mapping = null;

        String name = declaringClass.getQualifiedName();
        if (name != null) {
            mapping = mixinMappings.get(name);
        }
        while (true) {
            if (mapping != null) {
                String mapped = mapping.getMethodMapping(getSignature(method)).map(Mapping::getDeobfuscatedName).orElse(null);
                if (mapped != null) {
                    if (!mapped.equals(method.getName())) {
                        replaceIdentifier(expr, mapped);
                    }
                    return;
                }
                mapping = null;
            }
            while (mapping == null) {
                declaringClass = parentQueue.poll();
                if (declaringClass == null) return;

                PsiClass superClass = declaringClass.getSuperClass();
                if (superClass != null) {
                    parentQueue.offer(superClass);
                }
                for (PsiClass anInterface : declaringClass.getInterfaces()) {
                    parentQueue.offer(anInterface);
                }

                name = declaringClass.getQualifiedName();
                if (name == null) continue;
                mapping = map.getClassMapping(name).orElse(null);
            }
        }
    }

    private void map(PsiElement expr, PsiQualifiedNamedElement resolved) {
        String name = resolved.getQualifiedName();
        if (name == null) return;
        ClassMapping<?, ?> mapping = map.getClassMapping(name).orElse(null);
        if (mapping == null) return;
        String mapped = mapping.getDeobfuscatedName();
        if (mapped.equals(name)) return;
        mapped = mapped.replace('/', '.');

        if (expr.getText().equals(name)) {
            replace(expr, mapped);
            return;
        }
        replaceIdentifier(expr, mapped.substring(mapped.lastIndexOf('.') + 1));
    }

    private void map(PsiElement expr, PsiElement resolved) {
        if (resolved instanceof PsiField) {
            map(expr, (PsiField) resolved);
        } else if (resolved instanceof PsiMethod) {
            map(expr, (PsiMethod) resolved);
        } else if (resolved instanceof PsiClass || resolved instanceof PsiPackage) {
            map(expr, (PsiQualifiedNamedElement) resolved);
        }
    }

    // Note: Supports only Mixins with a single target (ignores others) and only ones specified via class literals
    private PsiClass getMixinTarget(PsiAnnotation annotation) {
        for (PsiNameValuePair pair : annotation.getParameterList().getAttributes()) {
            String name = pair.getName();
            if (name != null && !"value".equals(name)) continue;
            PsiAnnotationMemberValue value = pair.getValue();
            if (!(value instanceof PsiClassObjectAccessExpression)) continue;
            PsiTypeElement type = ((PsiClassObjectAccessExpression) value).getOperand();
            PsiJavaCodeReferenceElement reference = type.getInnermostComponentReferenceElement();
            if (reference == null) continue;
            return (PsiClass) reference.resolve();
        }
        return null;
    }

    private void remapAccessors(ClassMapping<?, ?> mapping) {
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(PsiMethod method) {
                PsiAnnotation annotation = method.getAnnotation(CLASS_ACCESSOR);
                if (annotation == null) return;

                String targetByName = method.getName();
                if (targetByName.startsWith("is")) {
                    targetByName = targetByName.substring(2);
                } else if (targetByName.startsWith("get") || targetByName.startsWith("set")) {
                    targetByName = targetByName.substring(3);
                } else {
                    targetByName = null;
                }
                if (targetByName != null) {
                    targetByName = targetByName.substring(0, 1).toLowerCase() + targetByName.substring(1);
                }

                String target = Arrays.stream(annotation.getParameterList().getAttributes())
                        .filter(it -> it.getName() == null || it.getName().equals("value"))
                        .map(PsiNameValuePair::getLiteralValue)
                        .findAny()
                        .orElse(targetByName);

                if (target == null) {
                    throw new IllegalArgumentException("Cannot determine accessor target for " + method);
                }

                String mapped = mapping.getFieldMapping(target).map(Mapping::getDeobfuscatedName).orElse(null);
                if (mapped != null && !mapped.equals(target)) {
                    // Update accessor target
                    String parameterList;
                    if (mapped.equals(targetByName)) {
                        // Mapped name matches implied target, can just remove the explict target
                        parameterList = "";
                    } else {
                        // Mapped name does not match implied target, need to set the target as annotation value
                        parameterList = "(\"" + StringUtil.escapeStringCharacters(mapped) + "\")";
                    }
                    replace(annotation.getParameterList(), parameterList);
                }
            }
        });
    }

    private void remapInjectsAndRedirects(ClassMapping<?, ?> mapping) {
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethod(PsiMethod method) {
                PsiAnnotation annotation = method.getAnnotation(CLASS_INJECT);
                if (annotation == null) {
                    annotation = method.getAnnotation(CLASS_REDIRECT);
                }
                if (annotation == null) return;

                for (PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
                    if (!"method".equals(attribute.getName())) continue;
                    // Note: mixin supports multiple targets, we do not (yet)
                    String literalValue = attribute.getLiteralValue();
                    if (literalValue == null) continue;
                    String mapped;
                    if (literalValue.contains("(")) {
                        mapped = mapping.getMethodMapping(MethodSignature.of(literalValue)).map(Mapping::getDeobfuscatedName).orElse(null);
                    } else {
                        mapped = null;
                        for (MethodMapping methodMapping : mapping.getMethodMappings()) {
                            if (methodMapping.getObfuscatedName().equals(literalValue)) {
                                String name = methodMapping.getDeobfuscatedName();
                                if (mapped != null && !mapped.equals(name)) {
                                    error(attribute, "Ambiguous mixin method \"" + literalValue + "\" maps to \"" + mapped + "\" and \"" + name + "\"");
                                }
                                mapped = name;
                            }
                        }
                    }
                    if (mapped != null && !mapped.equals(literalValue)) {
                        PsiAnnotationMemberValue value = attribute.getValue();
                        assert value != null;
                        replace(value, '"' + mapped + '"');
                    }
                }
            }
        });
    }

    private ClassMapping<?, ?> remapInternalType(String internalType, StringBuilder result) {
        if (internalType.charAt(0) == 'L') {
            String type = internalType.substring(1, internalType.length() - 1).replace('/', '.');
            ClassMapping<?, ?> mapping = map.getClassMapping(type).orElse(null);
            if (mapping != null) {
                result.append('L').append(mapping.getFullDeobfuscatedName()).append(';');
                return mapping;
            }
        }
        result.append(internalType);
        return null;
    }

    private String remapFullyQualifiedMethodOrField(String signature) {
        int ownerEnd = signature.indexOf(';');
        int argsBegin = signature.indexOf('(');
        int argsEnd = signature.indexOf(')');
        boolean method = argsBegin != -1;
        if (!method) {
            argsBegin = argsEnd = signature.indexOf(':');
        }
        String owner = signature.substring(0, ownerEnd + 1);
        String name = signature.substring(ownerEnd + 1, argsBegin);
        String returnType = signature.substring(argsEnd + 1);

        StringBuilder builder = new StringBuilder(signature.length() + 32);
        ClassMapping<?, ?> mapping = remapInternalType(owner, builder);
        String mapped = null;
        if (mapping != null) {
            mapped = (method
                    ? mapping.getMethodMapping(MethodSignature.of(signature.substring(ownerEnd + 1)))
                    : mapping.getFieldMapping(name))
                    .map(Mapping::getDeobfuscatedName)
                    .orElse(null);
        }
        builder.append(mapped != null ? mapped : name);
        if (method) {
            builder.append('(');
            String args = signature.substring(argsBegin + 1, argsEnd);
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (c != 'L') {
                    builder.append(c);
                    continue;
                }
                int end = args.indexOf(';', i);
                String arg = args.substring(i, end + 1);
                remapInternalType(arg, builder);
                i = end;
            }
            builder.append(')');
        } else {
            builder.append(':');
        }
        remapInternalType(returnType, builder);
        return builder.toString();
    }

    private void remapAtTargets() {
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                if (!CLASS_AT.equals(annotation.getQualifiedName())) {
                    super.visitAnnotation(annotation);
                    return;
                }

                for (PsiNameValuePair attribute : annotation.getParameterList().getAttributes()) {
                    if (!"target".equals(attribute.getName())) continue;
                    String signature = attribute.getLiteralValue();
                    if (signature == null) continue;
                    String newSignature = remapFullyQualifiedMethodOrField(signature);
                    if (!newSignature.equals(signature)) {
                        PsiAnnotationMemberValue value = attribute.getValue();
                        assert value != null;
                        replace(value, '"' + newSignature + '"');
                    }
                }
            }
        });
    }

    String remapFile() {
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(PsiClass psiClass) {
                PsiAnnotation annotation = psiClass.getAnnotation(CLASS_MIXIN);
                if (annotation == null) return;

                remapAtTargets();

                PsiClass target = getMixinTarget(annotation);
                if (target == null) return;
                String qualifiedName = target.getQualifiedName();
                if (qualifiedName == null) return;

                ClassMapping<?, ?> mapping = map.getClassMapping(qualifiedName).orElse(null);
                if (mapping == null) return;

                mixinMappings.put(psiClass.getQualifiedName(), mapping);

                if (!mapping.getFieldMappings().isEmpty()) {
                    remapAccessors(mapping);
                }
                if (!mapping.getMethodMappings().isEmpty()) {
                    remapInjectsAndRedirects(mapping);
                }
            }
        });

        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitField(PsiField field) {
                if (valid(field)) {
                    map(field, field);
                }
                super.visitField(field);
            }

            @Override
            public void visitMethod(PsiMethod method) {
                if (valid(method)) {
                    map(method, method);
                }
                super.visitMethod(method);
            }

            @Override
            public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
                if (valid(reference)) {
                    map(reference, reference.resolve());
                }
                super.visitReferenceElement(reference);
            }
        });

        return getResult(file.getText());
    }
}
