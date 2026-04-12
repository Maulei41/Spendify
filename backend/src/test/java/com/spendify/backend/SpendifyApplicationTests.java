package com.spendify.backend;

import net.sourceforge.tess4j.ITesseract;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SpendifyApplicationTests {

    @MockBean
    private ITesseract tesseract;

    @Test
    void contextLoads() {
    }

}
