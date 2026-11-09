package com.esibuy.esibuy_backend.repositorio;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.esibuy.esibuy_backend.modelo.PagoSuscripcion;

public interface RepositorioPagoSuscripcion extends MongoRepository<PagoSuscripcion, String> {
}