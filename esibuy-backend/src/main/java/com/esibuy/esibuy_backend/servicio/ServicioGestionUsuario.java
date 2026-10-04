package com.esibuy.esibuy_backend.servicio;

public class ServicioGestionUsuario {

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_NOMBRE = "nombre";
    private static final String CAMPO_APELLIDOS = "apellidos";
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_SEDE = "sede";
    private static final String CAMPO_FECHA_INCORPORACION = "fechaIncorporacion";
    private static final String CAMPO_AVATAR = "avatar";
    private static final String CAMPO_CONTRASENA = "contrasena";
    private static final String CAMPO_REPETIR_CONTRASENA = "repetirContrasena";

    private static final Logger log = LoggerFactory.getLogger(ServicioGestionUsuarios.class);

    private final RepositorioUsuario repositorioUsuario;
    private final ValidadorContrasena validadorContrasena;
    private final PasswordEncoder passwordEncoder;

    public ServicioGestionUsuario(RepositorioUsuario repositorioUsuario, ValidadorContrasena validadorContrasena, PasswordEncoder passwordEncoder) {
        this.repositorioUsuario = repositorioUsuario;
        this.validadorContrasena = validadorContrasena;
        this.passwordEncoder = passwordEncoder;
        
    }

    public UsuarioDTO crearAdministrador(SolicitudAltaAdministradorDTO solicitud){
        // Normalizar los datos de la solicitud (eliminando espacios, etc.)
        DatosComunes datos = DatosComunes.normalizar(solicitud.nombre(), solicitud.apellidos(),
                solicitud.email(),  solicitud.FechaIncorporacion(), solicitud.sede(),solicitud.avatar(), solicitud.contrasena(),
                solicitud.repetirContrasena());

        // Validar los datos personales y la contraseña, acumulando errores
        ErroresRegistro errores = new ErroresRegistro();
        validarDatosPersonales(datos, errores);
        validarContrasena(datos, null, errores);
        errores.lanzarSiHay();

        ErroresRegistro erroresExternos = new ErroresRegistro();
        comprobarDominioEmail(datos.email(), erroresExternos);
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = perfilComun(datos, catalogoAvatares::avatarPorDefectoCliente)
                .sede(datos.sede())
                .fechaIncorporacion(datos.fechaIncorporacion())
                .build();
        return crearCuenta(datos, rolDeAdministrador(solicitud.tipoAdministrador()), perfil);
    }

    private RespuestaRegistroDTO crearCuenta(DatosComunes datos, Rol rol, PerfilUsuario perfil) {
        if (repositorioUsuario.existePorEmail(datos.email())) {
            log.info("Registro rechazado: el email ya pertenece a una cuenta");
            throw new RegistroNoCompletadoException();
        }
        String hash = codificadorContrasena.encode(datos.contrasena());
        Usuario guardado = guardar(new Usuario(datos.email(), hash, rol, perfil));
        log.info("Cuenta creada: id={}, rol={}", guardado.getId(), rol);
        return new RespuestaRegistroDTO(guardado.getId(), guardado.getEmail(), perfil.getNombre(),
                MENSAJE_REGISTRO_CORRECTO);
    }

    private Usuario guardar(Usuario usuario) {
        try {
            return repositorioUsuario.save(usuario);
        } catch (DuplicateKeyException e) { // Puede ocurrir si llegan dos solicitudes simultaneas con el mismo email. No remitir el error a cliente.
            log.warn("Registro rechazado: clave duplicada al guardar (registro simultaneo)");
            throw new RegistroNoCompletadoException();
        }
    }




    /* validacion */

    //Reglas locales. Se decide mantener el campo nombreComercial para clientes, pero como nulo
    public void validarDatosPersonales(DatosComunes datos, ErroresRegistro errores) {
        int max = ServicioRegistro.LONGITUD_MAXIMA_CAMPO_TEXTO;
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_NOMBRE, datos.nombre(), max, errores);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_APELLIDOS, datos.apellidos(), max, errores);
        ReglasCamposRegistro.validarEmail(CAMPO_EMAIL, datos.email(), errores);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_SEDE, datos.sede(), max, errores);

    }

    private void validarContrasena(DatosComunes datos, String nombreComercial, ErroresRegistro errores) {
        DatosPersonalesContrasena datosPersonales =
                new DatosPersonalesContrasena(datos.nombre(), datos.apellidos(), datos.email(), nombreComercial);
        errores.anadirTodos(CAMPO_CONTRASENA, validadorContrasena.validar(datos.contrasena(), datosPersonales));
        // Comparacion exacta (tras NFC): una diferencia solo de mayusculas tambien cuenta (CP-PWD-14)
        if (datos.contrasena() != null && !datos.contrasena().equals(datos.repetirContrasena())) {
            errores.anadir(CAMPO_REPETIR_CONTRASENA, CodigoError.CONTRASENAS_NO_COINCIDEN);
        }
    }

    // Si el servicio de dominios falla, se aborta el registro con un error controlado (CP-REG-25). 
    private void comprobarDominioEmail(String email, ErroresRegistro errores) {
        boolean dominioValido;
        try {
            dominioValido = validadorDominioEmail.tieneDominioValido(email);
        } catch (RuntimeException e) {
            throw new ServicioNoDisponibleException(e);
        }
        if (!dominioValido) {
            errores.anadir(CAMPO_EMAIL, CodigoError.DOMINIO_EMAIL_INEXISTENTE);
        }
    }

    private record DatosComunes(String nombre, String apellidos, String dni, String email, String sede, LocalDateTime fechaIncorporacion,
                                String avatar, String contrasena, String repetirContrasena) {

        static DatosComunes normalizar(String nombre, String apellidos, String email, LocalDateTime fechaIncorporacion,
                                       String avatar, String contrasena, String repetirContrasena) {
            return new DatosComunes(
                    NormalizadorRegistro.texto(nombre),
                    NormalizadorRegistro.texto(apellidos),
                    NormalizadorRegistro.email(email),
                    fechaIncorporacion,
                    NormalizadorRegistro.texto(avatar),
                    NormalizadorRegistro.contrasena(contrasena),
                    NormalizadorRegistro.contrasena(repetirContrasena));
        }

    }


}