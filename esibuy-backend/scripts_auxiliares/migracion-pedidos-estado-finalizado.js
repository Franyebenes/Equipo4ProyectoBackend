// Migracion de MongoDB para los pedidos del cliente: anade el estado FINALIZADO (el cliente confirma que ha recibido
// el pedido y desde entonces puede valorar sus productos) al validador de la coleccion "pedidos".
//
// NO BORRA NADA. Solo sustituye el validador de "pedidos" (y crea la coleccion si no existia) y se asegura de que
// existe "valoraciones" con su indice unico. No toca los documentos que ya haya ni ninguna otra coleccion. Se puede
// ejecutar todas las veces que haga falta: si ya esta aplicada, no cambia nada.
//
// Hace falta en cualquier base creada con una version de init-esibuy.js anterior a este estado: sin aplicarla, el
// validador rechaza el cambio a FINALIZADO y confirmar la recepcion de un pedido falla.
//
// Como ejecutarlo (con mongosh instalado), indicando SIEMPRE la base de datos en la URI:
//   mongosh "mongodb://localhost:27017/ESIBuy" --file scripts_auxiliares/migracion-pedidos-estado-finalizado.js
//   mongosh "<URI de Atlas con la base de datos ESIBuy>" --file scripts_auxiliares/migracion-pedidos-estado-finalizado.js
// O abrirlo en VS Code con la extension "MongoDB for VS Code" conectada a tu servidor y pulsar "Play".
//
// Antes de aplicarlo en una base compartida (Atlas), haz una copia de seguridad (snapshot de Atlas o mongodump).
//
// IMPORTANTE: las definiciones de abajo deben ser identicas a las de src/test/resources/mongo/init-esibuy.js.

// --- Ajustes ---------------------------------------------------------------------------------------------

// Base de datos que usa el backend.
const BASE_DE_DATOS = 'ESIBuy';

// --- Definicion ------------------------------------------------------------------------------------------

const PEDIDOS = 'pedidos';
const VALORACIONES = 'valoraciones';

const validadorPedidos = {
   $jsonSchema: {
      bsonType: 'object',
      required: [ 'idCliente', 'estado', 'precioTotal', 'fecha', 'direccionEnvio', 'lineas' ],
      properties: {
         idCliente: { bsonType: 'objectId' },
         estado: {
            enum: [ 'PENDIENTE', 'PAGADO', 'ENVIADO', 'ENTREGADO', 'FINALIZADO', 'CANCELADO' ]
         },
         precioTotal: { bsonType: 'number', minimum: 0 },
         fecha: { bsonType: 'date' },
         direccionEnvio: { bsonType: 'object' },
         lineas: {
            bsonType: 'array',
            minItems: 1,
            items: {
               bsonType: 'object',
               required: [ 'idProducto', 'cantidad', 'precioUnitario' ]
            }
         }
      }
   }
};

const validadorValoraciones = {
   $jsonSchema: {
      bsonType: 'object',
      required: [ 'idProducto', 'idCliente', 'idPedido', 'puntos' ],
      properties: {
         idProducto: { bsonType: 'objectId' },
         idCliente: { bsonType: 'objectId' },
         idPedido: { bsonType: 'objectId' },
         puntos: {
            bsonType: 'int',
            minimum: 1,
            maximum: 5
         }
      }
   }
};

// --- Aplicacion ------------------------------------------------------------------------------------------

db = db.getSiblingDB(BASE_DE_DATOS);
print('Base de datos: ' + db.getName());

function crearOActualizar(nombre, validador) {
   if (db.getCollectionNames().includes(nombre)) {
      // Ya existe: se actualiza su validador (collMod lo sustituye entero; no modifica los documentos que haya)
      db.runCommand({ collMod: nombre, validator: validador });
      print('La coleccion ' + nombre + ' ya existia: validador actualizado.');
   } else {
      db.createCollection(nombre, { validator: validador });
      print('Coleccion ' + nombre + ' creada.');
   }
}

crearOActualizar(PEDIDOS, validadorPedidos);
db.getCollection(PEDIDOS).createIndex({ idCliente: 1 });

crearOActualizar(VALORACIONES, validadorValoraciones);
// Un cliente valora cada producto de cada pedido una sola vez, garantizado por la base de datos
db.getCollection(VALORACIONES).createIndex({ idCliente: 1, idProducto: 1, idPedido: 1 }, { unique: true });

print('Migracion aplicada. No se ha modificado ningun otro dato.');
