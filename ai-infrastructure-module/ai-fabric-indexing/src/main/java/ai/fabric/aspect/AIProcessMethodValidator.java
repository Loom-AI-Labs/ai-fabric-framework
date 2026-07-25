package ai.fabric.aspect;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessContractException;
import org.springframework.aop.support.AopUtils;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Fails startup for lifecycle methods that Spring proxy AOP cannot execute safely.
 */
public class AIProcessMethodValidator implements BeanPostProcessor {

    private final Set<Class<?>> validated = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
        throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (targetClass == null || !validated.add(targetClass)) {
            return bean;
        }
        for (Method method : methods(targetClass)) {
            AIProcess annotation = AnnotatedElementUtils.findMergedAnnotation(
                method,
                AIProcess.class
            );
            if (annotation == null) {
                continue;
            }
            validate(targetClass, method);
        }
        return bean;
    }

    private void validate(Class<?> targetClass, Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)
            || Modifier.isStatic(modifiers)
            || Modifier.isFinal(modifiers)) {
            throw new AIProcessContractException(
                "@AIProcess method must be public, non-static, and non-final: " + method
            );
        }
        if (Modifier.isFinal(targetClass.getModifiers())) {
            throw new AIProcessContractException(
                "@AIProcess declaring class must be proxyable and non-final: "
                    + targetClass.getName()
            );
        }
        if (isSelfInvoked(targetClass, method)) {
            throw new AIProcessContractException(
                "@AIProcess method is invoked from its own Spring bean and would bypass "
                    + "proxy interception: " + method
            );
        }
    }

    private Set<Method> methods(Class<?> type) {
        Set<Method> methods = new HashSet<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            methods.addAll(Set.of(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods;
    }

    private boolean isSelfInvoked(Class<?> targetClass, Method targetMethod) {
        String targetName = targetMethod.getName();
        String targetDescriptor = Type.getMethodDescriptor(targetMethod);
        Set<String> hierarchy = hierarchyInternalNames(targetClass);

        Class<?> current = targetClass;
        while (current != null && current != Object.class) {
            if (classInvokes(current, hierarchy, targetName, targetDescriptor)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private boolean classInvokes(
        Class<?> sourceClass,
        Set<String> hierarchy,
        String targetName,
        String targetDescriptor
    ) {
        String resourceName = Type.getInternalName(sourceClass) + ".class";
        ClassLoader classLoader = sourceClass.getClassLoader();
        try (InputStream input = classLoader == null
            ? ClassLoader.getSystemResourceAsStream(resourceName)
            : classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new AIProcessContractException(
                    "Unable to inspect @AIProcess bytecode for " + sourceClass.getName()
                );
            }
            boolean[] found = {false};
            new ClassReader(input).accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                    ) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(
                                int opcode,
                                String owner,
                                String name,
                                String descriptor,
                                boolean isInterface
                            ) {
                                if (hierarchy.contains(owner)
                                    && targetName.equals(name)
                                    && targetDescriptor.equals(descriptor)) {
                                    found[0] = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
            );
            return found[0];
        } catch (IOException exception) {
            throw new AIProcessContractException(
                "Unable to inspect @AIProcess bytecode for " + sourceClass.getName(),
                exception
            );
        }
    }

    private Set<String> hierarchyInternalNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            names.add(Type.getInternalName(current));
            for (Class<?> interfaceType : current.getInterfaces()) {
                names.add(Type.getInternalName(interfaceType));
            }
            current = current.getSuperclass();
        }
        return names;
    }
}
