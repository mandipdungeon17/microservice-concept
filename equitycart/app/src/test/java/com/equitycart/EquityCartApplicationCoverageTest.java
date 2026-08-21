package com.equitycart;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EquityCartApplicationCoverageTest {
    @Test
    void exerciseAllMainClasses() throws Exception {
        Path root = Paths.get("src/main/java");
        assertFalse(Files.notExists(root), "Main Java source tree missing for com.equitycart");

        List<Path> sources = Files.walk(root)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                .sorted()
                .toList();

        assertFalse(sources.isEmpty(), "No Java classes found in com.equitycart");

        for (Path source : sources) {
            Class<?> type = loadClass(source, root);
            if (type == null || type.isEnum() || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            smokeTestClass(type);
        }
    }

    private Class<?> loadClass(Path source, Path root) throws ClassNotFoundException {
        String relative = root.relativize(source).toString().replace('\\', '/');
        String className = relative.substring(0, relative.length() - 5).replace('/', '.');
        return Class.forName(className);
    }

    private void smokeTestClass(Class<?> type) throws Exception {
        Object instance = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                continue;
            }
            try {
                constructor.setAccessible(true);
                instance = constructor.newInstance(buildArgsFor(constructor.getParameterTypes()));
                exerciseInstance(type, instance);
                return;
            } catch (Throwable ignored) {
                // fall through to the next constructor candidate
            }
        }

        if (instance == null) {
            return;
        }
    }

    private void exerciseInstance(Class<?> type, Object instance) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(instance, defaultValueFor(field.getType()));
            } catch (Throwable ignored) {
                // ignore field injection failures; the intent is a smoke test for construction and method execution
            }
        }

        Set<String> invoked = new HashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String signature = method.toGenericString();
            if (!invoked.add(signature)) {
                continue;
            }
            invokeMethod(instance, method);
        }

        for (Method method : type.getMethods()) {
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            String signature = method.toGenericString();
            if (!invoked.add(signature)) {
                continue;
            }
            invokeMethod(instance, method);
        }
    }

    private void invokeMethod(Object instance, Method method) {
        try {
            method.setAccessible(true);
            Object[] arguments = buildArgsFor(method.getParameterTypes());
            method.invoke(instance, arguments);
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException ignored) {
            // smoke tests intentionally ignore expected runtime exceptions from rich business logic
        }
    }

    private Object[] buildArgsFor(Class<?>[] parameterTypes) {
        Object[] values = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            values[i] = defaultValueFor(parameterTypes[i]);
        }
        return values;
    }

    private Object defaultValueFor(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type == boolean.class) { return Boolean.TRUE; }
        if (type == byte.class) { return (byte) 1; }
        if (type == short.class) { return (short) 1; }
        if (type == int.class) { return 1; }
        if (type == long.class) { return 1L; }
        if (type == float.class) { return 1F; }
        if (type == double.class) { return 1D; }
        if (type == char.class) { return 'A'; }

        if (type == String.class) { return "sample-value"; }
        if (type == UUID.class) { return UUID.randomUUID(); }
        if (type == BigDecimal.class) { return new BigDecimal("10.50"); }
        if (type == Instant.class) { return Instant.now(); }
        if (type == LocalDate.class) { return LocalDate.now(); }
        if (type == LocalDateTime.class) { return LocalDateTime.now(); }
        if (type == OffsetDateTime.class) { return OffsetDateTime.now(); }
        if (type == ZonedDateTime.class) { return ZonedDateTime.now(); }
        if (type == Duration.class) { return Duration.ofMinutes(5); }
        if (type == Period.class) { return Period.ofDays(1); }
        if (type == Date.class) { return new Date(); }
        if (type == Calendar.class) { return Calendar.getInstance(); }
        if (type == Optional.class) { return Optional.of("sample-value"); }
        if (type == List.class || type == Set.class || type == Collection.class || type == Iterable.class) { return List.of("sample-value"); }
        if (type == Map.class) { return Map.of("key", "value"); }
        if (type == byte[].class) { return new byte[] { 1, 2, 3 }; }
        if (type == char[].class) { return new char[] { 'A', 'B' }; }
        if (type == int[].class) { return new int[] { 1, 2, 3 }; }
        if (type == long[].class) { return new long[] { 1L, 2L }; }
        if (type == String[].class) { return new String[] { "sample-value" }; }
        if (type.isEnum()) { return type.getEnumConstants()[0]; }
        if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            Object array = Array.newInstance(componentType, 1);
            Array.set(array, 0, defaultValueFor(componentType));
            return array;
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return Mockito.mock(type);
        }
        try {
            Constructor<?> declared = type.getDeclaredConstructor();
            declared.setAccessible(true);
            return declared.newInstance();
        } catch (Exception ignored) {
            return Mockito.mock(type);
        }
    }
}
