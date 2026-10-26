package com.esibuy.esibuy_backend.integracion;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.esibuy.esibuy_backend.servicio.Base32;
import com.esibuy.esibuy_backend.servicio.GeneradorTotp;
import com.jayway.jsonpath.JsonPath;
import com.mongodb.client.MongoCollection;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Utilidades de las pruebas de integracion que necesitan una cuenta o un registro, ahora que el registro exige el 2FA.
 *  - {@link #registrar}: el registro por HTTP completo, como lo haria el navegador: pide el QR, calcula el codigo con el secreto
 *    (como la aplicacion autenticadora) y envia la solicitud con ese codigo y la misma sesion. Si el correo no es valido, el QR
 *    no se concede y se envia la solicitud sin pendiente, para que sea el registro quien devuelva los errores.
 *  - {@link #insertarCliente}: una cuenta SIN 2FA creada directamente en la base de datos, para las pruebas que solo necesitan
 *    una cuenta existente (el login, las sesiones...). Las cuentas anteriores al 2FA son asi.
 */
final class RegistroConDosFactoresDePrueba {

    private RegistroConDosFactoresDePrueba() {
    }

    static ResultActions registrar(MockMvc mockMvc, ObjectMapper objectMapper, GeneradorTotp totp, Object solicitud)
            throws Exception {
        Map<String, Object> cuerpo = new HashMap<>(
                objectMapper.convertValue(solicitud, new TypeReference<Map<String, Object>>() { }));
        MockHttpSession sesion = new MockHttpSession();
        Object email = cuerpo.get("email");
        MvcResult qr = mockMvc.perform(post("/api/auth/registro/2fa").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email == null ? "" : email)))).andReturn();
        if (qr.getResponse().getStatus() == 200) {
            String secreto = JsonPath.read(qr.getResponse().getContentAsString(), "$.secreto");
            cuerpo.put("codigoDosFactores", totp.codigo(Base32.decodificar(secreto), totp.pasoActual()));
        }
        return mockMvc.perform(post("/api/auth/registro").session(sesion).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(cuerpo)));
    }

    static void insertarCliente(MongoCollection<Document> usuarios, PasswordEncoder codificador, String email,
            String contrasena, String estado) {
        usuarios.insertOne(new Document("email", email)
                .append("password", codificador.encode(contrasena))
                .append("estado", estado)
                .append("rol", List.of("CLIENTE"))
                .append("perfil", new Document("nombre", "Ana").append("apellidos", "Garcia")));
    }
}
