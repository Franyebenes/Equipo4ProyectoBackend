package com.esibuy.esibuy_backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;
import java.util.Map;

@Document(collection = "usuarios")
public class User {

    @Id
    private String id;
    private String email;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private List<String> rol;
    private String estado;
    private Map<String, Object> perfil;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public List<String> getRol() { return rol; }
    public void setRol(List<String> rol) { this.rol = rol; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public Map<String, Object> getPerfil() { return perfil; }
     public void setPerfil(Map<String, Object> perfil) { this.perfil = perfil; }
}