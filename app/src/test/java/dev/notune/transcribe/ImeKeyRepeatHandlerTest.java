package dev.notune.transcribe;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ImeKeyRepeatHandlerTest {

    @Test
    public void testKeyRepeatStartAndStop() {
        AtomicInteger counter = new AtomicInteger(0);
        ImeKeyRepeatHandler handler = new ImeKeyRepeatHandler(null, 400, 50, counter::incrementAndGet);

        assertFalse(handler.isRepeating());
        handler.start();
        assertTrue(handler.isRepeating());
        // Immediate first invocation
        assertEquals(1, counter.get());

        handler.stop();
        assertFalse(handler.isRepeating());
    }
}
