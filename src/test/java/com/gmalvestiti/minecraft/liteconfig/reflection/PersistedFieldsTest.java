package com.gmalvestiti.minecraft.liteconfig.reflection;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedFieldsTest {

    @Test
    void testReturnsFieldsInStableNameOrderFromChildToParent() {
        assertEquals(
            List.of("childA", "childZ", "parentA", "parentZ"),
            PersistedFields.of(Child.class).stream().map(Field::getName).toList());
    }

    @Test
    void testRejectsNullTypes() {
        assertThrows(NullPointerException.class, () -> PersistedFields.of(null));
    }

    @Test
    void testCustomObjectsAreDescendableButJdkValuesAreLeaves() throws NoSuchFieldException {
        Field section = Types.class.getDeclaredField("section");
        Field text = Types.class.getDeclaredField("text");
        Field values = Types.class.getDeclaredField("values");

        assertTrue(PersistedFields.descendable(section.getGenericType()));
        assertFalse(PersistedFields.descendable(text.getGenericType()));
        assertFalse(PersistedFields.descendable(values.getGenericType()));
    }

    @Test
    void testRecordsInaccessibleInheritedFields() {
        assertFalse(PersistedFields.inaccessible(InaccessibleFields.class).isEmpty());
    }

    static class Parent {
        int parentZ;
        int parentA;
    }

    static class Child extends Parent {
        int childZ;
        int childA;
    }

    static class Types {
        Section section;
        String text;
        List<String> values;
    }

    static class Section {
        int value;
    }

    static class InaccessibleFields extends ArrayList<String> {
    }
}
