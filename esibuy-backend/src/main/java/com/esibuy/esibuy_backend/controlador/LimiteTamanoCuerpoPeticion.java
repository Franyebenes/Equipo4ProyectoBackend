package com.esibuy.esibuy_backend.controlador;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import com.esibuy.esibuy_backend.excepcion.CuerpoDemasiadoGrandeException;

// Rechaza los cuerpos @RequestBody de mas de {@value #TAMANO_MAXIMO_BYTES} bytes antes de deserializarlos. Jackson admite por defecto cadenas de hasta 100 millones de caracteres, asi que sin este limite
 //un JSON enorme se cargaria entero en memoria. comprueba la cabecera Content-Length y, por si no viene (cuerpo por trozos), tambien se cuentan los bytes mientras se leen.
 
@ControllerAdvice
public class LimiteTamanoCuerpoPeticion extends RequestBodyAdviceAdapter {

    public static final int TAMANO_MAXIMO_BYTES = 16 * 1024;

    @Override
    public boolean supports(MethodParameter parametro, Type tipoDestino,
                            Class<? extends HttpMessageConverter<?>> tipoConversor) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage mensaje, MethodParameter parametro, Type tipoDestino,
                                           Class<? extends HttpMessageConverter<?>> tipoConversor) {
        if (mensaje.getHeaders().getContentLength() > TAMANO_MAXIMO_BYTES) {
            throw new CuerpoDemasiadoGrandeException();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() throws IOException {
                return new FlujoLimitado(mensaje.getBody());
            }

            @Override
            public HttpHeaders getHeaders() {
                return mensaje.getHeaders();
            }
        };
    }

    // Lanza CuerpoDemasiadoGrandeException en cuanto se lee un byte mas del maximo. 
    private static final class FlujoLimitado extends FilterInputStream {

        private long leidos;

        private FlujoLimitado(InputStream original) {
            super(original);
        }

        @Override
        public int read() throws IOException {
            int byteLeido = super.read();
            if (byteLeido != -1) {
                contar(1);
            }
            return byteLeido;
        }

        @Override
        public int read(byte[] destino, int desde, int longitud) throws IOException {
            int cantidad = super.read(destino, desde, longitud);
            if (cantidad > 0) {
                contar(cantidad);
            }
            return cantidad;
        }

        private void contar(int cantidad) {
            leidos += cantidad;
            if (leidos > TAMANO_MAXIMO_BYTES) {
                throw new CuerpoDemasiadoGrandeException();
            }
        }
    }
}
