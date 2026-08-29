package com.gmalvestiti.minecraft.liteconfig.api;

import com.gmalvestiti.minecraft.liteconfig.api.spi.Violation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateResultTest {

    @Test
    void testReportsAnAcceptedResultWithoutViolations() {
        UpdateResult published = UpdateResult.published();

        assertTrue(published.accepted());
        assertTrue(published.violations().isEmpty());
        assertSame(UpdateResult.published(), published, "Published carries no data, so it is shared");
        assertEquals("UpdateResult.Published", published.toString());
    }

    @Test
    void testReportsARejectedResultWithItsViolations() {
        UpdateResult rejected = UpdateResult.rejected(List.of(Violation.of("rule", "broken")));

        assertFalse(rejected.accepted());
        assertEquals(List.of("rule"), rejected.violations().stream().map(Violation::id).toList());
    }

    @Test
    void testCopiesAndFreezesTheViolationsItReceives() {
        List<Violation> mutable = new ArrayList<>();
        mutable.add(Violation.of("rule", "broken"));

        UpdateResult rejected = UpdateResult.rejected(mutable);
        mutable.clear();

        assertEquals(1, rejected.violations().size(), "the result must not alias the caller's list");
        assertThrows(UnsupportedOperationException.class, () -> rejected.violations().clear());
    }

    @Test
    void testRejectsMissingViolations() {
        assertThrows(NullPointerException.class, () -> UpdateResult.rejected(null));
    }
}

