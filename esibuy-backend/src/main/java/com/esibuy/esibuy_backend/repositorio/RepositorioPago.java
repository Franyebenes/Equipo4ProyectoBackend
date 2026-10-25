package com.esibuy.esibuy_backend.repositorio;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.esibuy.esibuy_backend.modelo.Pago;

public interface RepositorioPago extends MongoRepository<Pago, String> {
}