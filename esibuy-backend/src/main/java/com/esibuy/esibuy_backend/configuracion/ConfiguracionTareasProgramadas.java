package com.esibuy.esibuy_backend.configuracion;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Activa las tareas programadas (@Scheduled), como la que vacia los carritos abandonados
@Configuration
@EnableScheduling
public class ConfiguracionTareasProgramadas {
}
