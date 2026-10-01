// 1. Seleccion la base de datos
db = db.getSiblingDB("ESIBuy");

// 2. Limpieza de colecciones previas
db.usuarios.drop();
db.categorias.drop();
db.productos.drop();
db.pedidos.drop();
db.valoraciones.drop();
db.incidencias.drop();

// ==========================================
// 1. COLECCIÓN: USUARIOS (users)
// ==========================================
db.createCollection("usuarios", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "email", "password", "rol", "perfil" ],
         properties: {
            email: { bsonType: "string" },
            password: { bsonType: "string" },
            estado: { enum: [ "ACTIVO", "BLOQUEADO", "DESACTIVADO" ] },
            rol: {
               bsonType: "array",
               items: { bsonType: "string", enum: [ "ADMIN", "VENDEDOR", "CLIENTE", "PREMIUM" ] },
               minItems: 1,
               maxItems: 1 // RESTRICCIÓN: Solo permite 1 rol por usuario
            },
            perfil: {
               bsonType: "object",
               required: ["nombre", "apellidos"],
               properties: {
                  nombre: { bsonType: "string" },
                  apellidos: { bsonType: "string" },
                  dni: { bsonType: "string" },
                  fechaNacimiento: { bsonType: "date" },
                  telefono: {
                     bsonType: "string",
                     pattern: "^[0-9]{9}$" // Exige 9 números exactos
                  },
                  imagen: { bsonType: "string" }, // Avatar
                  nombreComercial: { bsonType: "string" },
                  idCategoriaPrincipal: { bsonType: "objectId" },
                  sede: { bsonType: "string" },
                  fechaIncorporacion: { bsonType: "date" }
               }
            }
         }
      }
   }
});

db.usuarios.createIndex({ "email": 1 }, { unique: true });
db.usuarios.createIndex({ "perfil.nombreComercial": 1 }, { unique: true, partialFilterExpression: { "perfil.nombreComercial": { $exists: true } } });

// ==========================================
// 2. COLECCIÓN: CATEGORÍAS (categories)
// ==========================================
db.createCollection("categorias", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "nombre" ],
         properties: {
            nombre: { bsonType: "string" }
         }
      }
   }
});

// ==========================================
// 3. COLECCIÓN: PRODUCTOS (products)
// ==========================================
db.createCollection("productos", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "idVendedor", "idCategorias", "nombre", "precio", "stock", "visible" ],
         properties: {
            idVendedor: { bsonType: "objectId" },
            nombre: { bsonType: "string" },
            descripcion: { bsonType: "string" },
            imagen: { bsonType: "string" },
            idCategorias: {
               bsonType: "array",
               items: { bsonType: "objectId" },
               minItems: 1
            },
            precio: {
               bsonType: "object",
               required: [ "base" ],
               properties: {
                  base: { bsonType: "number", minimum: 0 },
                  descuento: { bsonType: "number", minimum: 0, maximum: 100 },
                  descuentoPremium: { bsonType: "number", minimum: 0, maximum: 100 }
               }
            },
            stock: { bsonType: "int", minimum: 0 },
            visible: { bsonType: "bool" } // Equivalente a "active"
         }
      }
   }
});

db.productos.createIndex({ "idVendedor": 1 });
db.productos.createIndex({ "idCategorias": 1 });

// ==========================================
// 4. COLECCIÓN: PEDIDOS (orders)
// ==========================================
db.createCollection("pedidos", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "idCliente", "estado", "precioTotal", "fecha", "direccionEnvio", "lineas" ],
         properties: {
            idCliente: { bsonType: "objectId" },
            estado: {
               enum: [ "PENDIENTE", "PAGADO", "ENVIADO", "ENTREGADO", "CANCELADO" ]
            },
            precioTotal: { bsonType: "number", minimum: 0 },
            fecha: { bsonType: "date" },
            direccionEnvio: { bsonType: "object" },
            lineas: { // Equivalente a items
               bsonType: "array",
               minItems: 1,
               items: {
                  bsonType: "object",
                  required: ["idProducto", "cantidad", "precioUnitario"]
               }
            }
         }
      }
   }
});

db.pedidos.createIndex({ "idCliente": 1 });

// ==========================================
// 5. COLECCIÓN: VALORACIONES (reviews)
// ==========================================
db.createCollection("valoraciones", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "idProducto", "idCliente", "idPedido", "puntos" ],
         properties: {
            idProducto: { bsonType: "objectId" },
            idCliente: { bsonType: "objectId" },
            idPedido: { bsonType: "objectId" },
            puntos: {
               bsonType: "int",
               minimum: 1,
               maximum: 5
            }
         }
      }
   }
});

db.valoraciones.createIndex({ "idCliente": 1, "idProducto": 1, "idPedido": 1 }, { unique: true });

// ==========================================
// 6. COLECCIÓN: INCIDENCIAS (incidents)
// ==========================================
db.createCollection("incidencias", {
   validator: {
      $jsonSchema: {
         bsonType: "object",
         required: [ "idUsuario", "descripcion", "estado" ],
         properties: {
            idUsuario: { bsonType: "objectId" },
            estado: {
               enum: [ "ABIERTA", "EN_PROCESO", "RESUELTA" ]
            },
            descripcion: { bsonType: "string" }
         }
      }
   }
});
