package com.datagraph.bank.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceTitleNormalizerTest {
    @Test
    void replacesSequenceOnlyNameWithEvidenceSemantics() {
        assertThat(EvidenceTitleNormalizer.meaningfulTitle(
                "材料事实证据1", "高某使用他人银行卡参与资金流转"))
                .isEqualTo("使用他人银行卡或账户参与资金流转");
    }

    @Test
    void keepsAnExistingMeaningfulName() {
        assertThat(EvidenceTitleNormalizer.meaningfulTitle(
                "资金转入指定账户", "原文"))
                .isEqualTo("资金转入指定账户");
    }
}
