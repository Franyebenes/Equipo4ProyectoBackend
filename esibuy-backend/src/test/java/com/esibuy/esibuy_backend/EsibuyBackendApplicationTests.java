package com.esibuy.esibuy_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// El pepper es obligatorio para arrancar (ConfiguracionContrasena); en produccion llega por ESIBUY_PEPPER
@SpringBootTest(properties = "esibuy.seguridad.pepper=pepper-de-test")
class EsibuyBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
