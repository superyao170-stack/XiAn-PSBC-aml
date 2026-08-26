package com.datagraph.bank.common.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataTextValidatorTest {

    @Test
    void acceptsChineseMetadataAndSingleQuestionPunctuation() {
        assertEquals("结构化资金交易事件",
                MetadataTextValidator.requireClean(" 结构化资金交易事件 ", "frameName"));
        assertTrue(MetadataTextValidator.isClean("是否异常?"));
    }

    @Test
    void rejectsLossyQuestionMarkRuns() {
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTextValidator.requireClean("?????????", "frameName"));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTextValidator.requireClean("金???额", "slotName"));
    }

    @Test
    void rejectsUnicodeReplacementCharacter() {
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTextValidator.requireClean("结构化资\uFFFD交易", "frameName"));
    }
}
