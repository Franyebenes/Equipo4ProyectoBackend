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

import com.esibuy.esibuy_backend.dto.ProductoAltaDTO;
import com.esibuy.esibuy_backend.excepcion.CuerpoDemasiadoGrandeException;

/**
 * Rechaza los cuerpos @RequestBody de mas de {@value #TAMANO_MAXIMO_BYTES} bytes antes de deserializarlos
 * (CP-SEG-11). Jackson admite por defecto cadenas de hasta 100 millones de caracteres, asi que sin este limite un
 * JSON enorme se cargaria entero en memoria.
 *
 * <p>Excepcion: el alta de producto (HU15.1) lleva una imagen en base64, asi que admite hasta
 * {@value #TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES} bytes. Sigue limitado, y esa ruta solo la puede usar un vendedor.
 *
 * <p>Se comprueba la cabecera Content-Length y, por si no viene (cuerpo por trozos), tambien se cuentan los bytes
 * mientras se leen.
 */
@ControllerAdvice
public class LimiteTamanoCuerpoPeticion extends RequestBodyAdviceAdapter {

    public static final int TAMANO_MAXIMO_BYTES = 16 * 1024;
    // HU15.1: imagen de hasta 1 MB, que en base64 ocupa unos 1,4 MB
    public static final int TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES = 2 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parametro, Type tipoDestino,
                            Class<? extends HttpMessageConverter<?>> tipoConversor) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage mensaje, MethodParameter parametro, Type tipoDestino,
                                           Class<? extends HttpMessageConverter<?>> tipoConversor) {
        long limite = limitePara(tipoDestino); // HU15.1
        if (mensaje.getHeaders().getContentLength() > limite) {
            throw new CuerpoDemasiadoGrandeException();
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() throws IOException {
                return new FlujoLimitado(mensaje.getBody(), limite);
            }

            @Override
            public HttpHeaders getHeaders() {
                return mensaje.getHeaders();
            }
        };
    }

    // HU15.1: solo el alta de producto tiene un limite mayor; el resto de peticiones sigue con 16 KB
    private static long limitePara(Type tipoDestino) {
        return tipoDestino == ProductoAltaDTO.class ? TAMANO_MAXIMO_ALTA_PRODUCTO_BYTES : TAMANO_MAXIMO_BYTES;
    }

    /** Lanza CuerpoDemasiadoGrandeException en cuanto se lee un byte mas del maximo. */
    private static final class FlujoLimitado extends FilterInputStream {

        private final long limite;
        private long leidos;

        private FlujoLimitado(InputStream original, long limite) {
            super(original);
            this.limite = limite;
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
            if (leidos > limite) {
                throw new CuerpoDemasiadoGrandeException();
            }
        }
    }
}
