package com.esibuy.esibuy_backend.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;


@Document(collection = "usuarios")
public class Usuario {

    @Id 
    private String id;
    private String nombre;

    private String correo;

    private String contrasena;
    private RolUsuario rol;
    private EstadoUsuario estado;
    
    


}
