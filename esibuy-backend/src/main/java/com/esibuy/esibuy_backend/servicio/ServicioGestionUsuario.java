package com.esibuy.esibuy_backend.servicio;

public class ServicioGestionUsuario {

    // Nombres de campo de los errores: coinciden con los del JSON de la solicitud
    private static final String CAMPO_NOMBRE = "nombre";
    private static final String CAMPO_APELLIDOS = "apellidos";
    private static final String CAMPO_EMAIL = "email";
    private static final String CAMPO_SEDE = "sede";
    private static final String CAMPO_AVATAR = "avatar";
    private static final String CAMPO_CONTRASENA = "contrasena";
    private static final String CAMPO_REPETIR_CONTRASENA = "repetirContrasena";

    private static final Logger log = LoggerFactory.getLogger(ServicioGestionUsuarios.class);

    private final RepositorioUsuario repositorioUsuario;
    private final ValidadorContrasena validadorContrasena;
    private final PasswordEncoder codificadorContrasena;
    private final CatalogoAvatares catalogoAvatares;

    // para la fecha de incorporacoin
    private final Clock reloj;

    private static final Set<EstadoUsuario> PUEDEN_BLOQUEARSE =
            EnumSet.of(EstadoUsuario.ACTIVO, EstadoUsuario.DESACTIVADO);
    private static final Set<EstadoUsuario> PUEDEN_DESBLOQUEARSE = EnumSet.of(EstadoUsuario.BLOQUEADO);
    private static final Set<EstadoUsuario> PUEDEN_ELIMINARSE =
            EnumSet.of(EstadoUsuario.ACTIVO, EstadoUsuario.DESACTIVADO, EstadoUsuario.BLOQUEADO);



    public ServicioGestionUsuario(RepositorioUsuario repositorioUsuario, ValidadorContrasena validadorContrasena, 
        PasswordEncoder passwordEncoder, CatalogoAvatares catalogoAvatares, Clock reloj) {
        this.repositorioUsuario = repositorioUsuario;
        this.validadorContrasena = validadorContrasena;
        this.passwordEncoder = passwordEncoder;
        this.catalogoAvatares = catalogoAvatares;
        this.reloj = reloj;
    }

    public RespuestaRegistroDTO crearAdministrador(SolicitudAltaAdministradorDTO solicitud){
        //Normalizar los datos de la solicitud (eliminando espacios, etc.)
        DatosComunes datos = DatosComunes.normalizar(solicitud.nombre(), solicitud.apellidos(),
                solicitud.email(),  solicitud.FechaIncorporacion(), solicitud.sede(),solicitud.avatar(), solicitud.contrasena(),
                solicitud.repetirContrasena());

        // Validar los datos personales y la contraseña, acumulando errores
        ErroresRegistro errores = validarDatosPersonales(datos);
        errores.lanzarSiHay();

        ErroresRegistro erroresExternos = new ErroresRegistro();
        comprobarDominioEmail(datos.email(), erroresExternos);
        erroresExternos.lanzarSiHay();

        PerfilUsuario perfil = PerfilUsuario.builder()
                .nombre(nombre).apellidos(apellidos).sede(sede).avatarUrl(avatar)
                .fechaIncorporacion(LocalDate.now(reloj)).build();
        
        return crearCuenta(datos, Rol.ADMINISTRADOR, perfil);
    }

    public PaginaDTO<UsuarioDTO> listar(int pagina, int tamano, Rol rol, EstadoUsuario estado) {
        // Math.clamp(value, min, max) keeps the size inside the allowed range; Sort.by uses a constant field
        // name, never a value coming from the client.
        Pageable pageable = PageRequest.of(Math.max(0, pagina), Math.clamp(tamano, 1, TAMANO_MAXIMO_PAGINA),
                Sort.by(CAMPO_EMAIL);
        return PaginaDTO.desde(buscarPagina(rol, estado, pageable).map(UsuarioDTO::desde));
    }

    public UsuarioDTO modificar(String id, SolicitudModificacionUsuarioDTO solicitud) {
        String nombre = NormalizadorRegistro.texto(solicitud.nombre());
        String apellidos = NormalizadorRegistro.texto(solicitud.apellidos());
        String dni = NormalizadorRegistro.texto(solicitud.dni());
        String telefono = NormalizadorRegistro.texto(solicitud.telefono());
        String sede = NormalizadorRegistro.texto(solicitud.sede());

        ErroresRegistro errores = new ErroresRegistro();
        validarDatosPersonales(nombre, apellidos, sede, errores);
        validarTextoOpcional(CAMPO_DNI, dni, errores);
        ReglasCamposRegistro.validarTelefono(CAMPO_TELEFONO, telefono, errores);
        errores.lanzarSiHay();

        Usuario usuario = buscarNoEliminado(id);
        usuario.actualizarDatosPersonales(nombre, apellidos, dni, telefono, sede);
        Usuario guardado = repositorioUsuario.save(usuario);
        log.info("Usuario modificado: id={}", guardado.getId());
        return UsuarioDTO.desde(guardado);
    }

    public UsuarioDTO bloquear(String id, String emailSolicitante) {
        return cambiarEstado(id, emailSolicitante, PUEDEN_BLOQUEARSE, EstadoUsuario.BLOQUEADO);
    }

    public UsuarioDTO desbloquear(String id, String emailSolicitante) {
        return cambiarEstado(id, emailSolicitante, PUEDEN_DESBLOQUEARSE, EstadoUsuario.ACTIVO);
    }

    public void eliminar(String id, String emailSolicitante) {
        cambiarEstado(id, emailSolicitante, PUEDEN_ELIMINARSE, EstadoUsuario.ELIMINADO);
    }

    // helpers 

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

    private UsuarioDTO cambiarEstado(String id, String emailSolicitante, Set<EstadoUsuario> origenesValidos,
                                     EstadoUsuario destino) {
        Usuario usuario = buscarNoEliminado(id);
        // Case-insensitive because emails are stored lowercase but the authentication name may differ in case.
        if (usuario.getEmail().equalsIgnoreCase(emailSolicitante) || !origenesValidos.contains(usuario.getEstado())) {
            throw new OperacionNoPermitidaException();
        }
        usuario.cambiarEstado(destino);
        Usuario guardado = repositorioUsuario.save(usuario);
        log.info("Estado de usuario cambiado: id={}, nuevoEstado={}", guardado.getId(), destino);
        return UsuarioDTO.desde(guardado);
    }

    private Page<Usuario> buscarPagina(Rol rol, EstadoUsuario estado, Pageable pageable) {
        if (rol == null) {
            return estado == null
                    ? repositorioUsuario.findByEstadoNot(EstadoUsuario.ELIMINADO, pageable)
                    : repositorioUsuario.findByEstado(estado, pageable);
        }
        return estado == null
                ? repositorioUsuario.findPorRolExcluyendoEstado(rol, EstadoUsuario.ELIMINADO, pageable)
                : repositorioUsuario.findPorRolYEstado(rol, estado, pageable);
    }

    private Usuario buscarNoEliminado(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new UsuarioNoEncontradoException();
        }
        return repositorioUsuario.findByIdAndEstadoNot(id, EstadoUsuario.ELIMINADO)
                .orElseThrow(UsuarioNoEncontradoException::new);
    }



    /* validacion */

    //Reglas locales. 
    public ErroresRegistro validarDatosPersonales(DatosComunes datos) {
        ErroresRegistro errores = new ErroresRegistro();
        int max = ServicioRegistro.LONGITUD_MAXIMA_CAMPO_TEXTO;

        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_NOMBRE, datos.nombre(), max, errores);
        ReglasCamposRegistro.validarTextoObligatorio(CAMPO_APELLIDOS, datos.apellidos(), max, errores);
        ReglasCamposRegistro.validarEmail(CAMPO_EMAIL, datos.email(), errores);
        ReglasCamposRegistro.validarTextoOpcional(CAMPO_SEDE, datos.sede(), max, errores);
        if (datos.avatar() != null && !catalogoAvatares.esAvatarValido(datos.avatar())) {
            errores.anadir(CAMPO_AVATAR, CodigoError.AVATAR_NO_PERMITIDO);
        }
        validarContrasena(datos, errores);

        return errores;

    }

    private void validarContrasena(DatosComunes datos,   ErroresRegistro errores) {
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

        static DatosComunes normalizar(String nombre, String apellidos, String email, LocalDateTime fechaIncorporacion,String sede,
                                       String avatar, String contrasena, String repetirContrasena) {
            return new DatosComunes(
                    NormalizadorRegistro.texto(nombre),
                    NormalizadorRegistro.texto(apellidos),
                    NormalizadorRegistro.email(email),
                    NormalizadorRegistro.texto(sede),
                    fechaIncorporacion,
                    NormalizadorRegistro.texto(avatar),
                    NormalizadorRegistro.contrasena(contrasena),
                    NormalizadorRegistro.contrasena(repetirContrasena));
        }

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


}