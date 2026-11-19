package com.github.stormino.api.v1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DescribeCronTest {

    @Test
    void describesDailyTimes() {
        assertEquals("daily at 03:00 and 06:00", SystemController.describeCron("0 0 3,6 * * *"));
        assertEquals("daily at 01:00, 03:00 and 05:00", SystemController.describeCron("0 0 1,3,5 * * *"));
        assertEquals("daily at 04:30", SystemController.describeCron("0 30 4 * * *"));
        assertEquals("cron 0 0 */2 * * *", SystemController.describeCron("0 0 */2 * * *"));
    }
}
