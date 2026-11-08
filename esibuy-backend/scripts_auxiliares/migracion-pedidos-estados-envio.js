// Migracion de MongoDB para el seguimiento del envio de los pedidos (Procesar estado del pedido y Estado del envio):
//  - anade los estados EN_PREPARACION y EN_REPARTO al validador de la coleccion "pedidos",
//  - admite los campos nuevos fechaActualizacion, fechaEntregaEstimada y seguimiento (opcionales: los pedidos que ya
//    existen no los tienen y siguen siendo validos),
//  - y crea el indice por vendedor y fecha para listar los pedidos recibidos de cada vendedor.
//
// NO BORRA NADA. Solo sustituye el validador de "pedidos" y crea los indices que falten. No toca los documentos que
// ya haya ni ninguna otra coleccion. Se puede ejecutar todas las veces que haga falta: si ya esta aplicada, no cambia
// nada.
//
// Hace falta en cualquier base creada con una version de init-esibuy.js anterior a este estado: sin aplicarla, el
// validador rechaza los estados nuevos y el vendedor no puede cambiar el estado de sus pedidos.
//
// Como ejecutarlo (con mongosh instalado), indicando SIEMPRE la base de datos en la URI:
//   mongosh "mongodb://localhost:27017/ESIBuy" --file scripts_auxiliares/migracion-pedidos-estados-envio.js
//   mongosh "<URI de Atlas con la base de datos ESIBuy>" --file scripts_auxiliares/migracion-pedidos-estados-envio.js
// O abrirlo en VS Code con la extension "MongoDB for VS Code" conectada a tu servidor y pulsar "Play".
//
// Antes de aplicarlo en una base compartida (Atlas), haz una copia de seguridad (snapshot de Atlas o mongodump).
//
// IMPORTANTE: las definiciones de abajo deben ser identicas a las de src/test/resources/mongo/init-esibuy.js
// (lo comprueba MigracionPedidosEstadosEnvioMongoTest).

// --- Ajustes ---------------------------------------------------------------------------------------------

// Base de datos que usa el backend.
const BASE_DE_DATOS = 'ESIBuy';

// --- Definicion ------------------------------------------------------------------------------------------

const PEDIDOS = 'pedidos';

const ESTADOS = [ 'PENDIENTE', 'PAGADO', 'EN_PREPARACION', 'ENVIADO', 'EN_REPARTO', 'ENTREGADO', 'FINALIZADO',
                  'CANCELADO' ];

const validadorPedidos = {
   $jsonSchema: {
      bsonType: 'object',
      required: [ 'idCliente', 'estado', 'precioTotal', 'fecha', 'direccionEnvio', 'lineas' ],
      properties: {
         idCliente: { bsonType: 'objectId' },
         estado: { enum: ESTADOS },
         precioTotal: { bsonType: 'number', minimum: 0 },
         fecha: { bsonType: 'date' },
         fechaActualizacion: { bsonType: 'date' },
         fechaEntregaEstimada: { bsonType: 'date' },
         seguimiento: {
            bsonType: 'array',
            items: {
               bsonType: 'object',
               required: [ 'estado', 'fecha' ],
               properties: {
                  estado: { enum: ESTADOS },
                  fecha: { bsonType: 'date' }
               }
            }
         },
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

// --- Aplicacion ------------------------------------------------------------------------------------------

db = db.getSiblingDB(BASE_DE_DATOS);
print('Base de datos: ' + db.getName());

if (db.getCollectionNames().includes(PEDIDOS)) {
   // Ya existe: se actualiza su validador (collMod lo sustituye entero; no modifica los documentos que haya)
   db.runCommand({ collMod: PEDIDOS, validator: validadorPedidos });
   print('La coleccion ' + PEDIDOS + ' ya existia: validador actualizado.');
} else {
   db.createCollection(PEDIDOS, { validator: validadorPedidos });
   print('Coleccion ' + PEDIDOS + ' creada.');
}

// Los mismos indices que init-esibuy.js. createIndex no hace nada si el indice ya existe
const pedidos = db.getCollection(PEDIDOS);
pedidos.createIndex({ idCliente: 1 });
pedidos.createIndex({ idVendedor: 1, fecha: -1 });
pedidos.createIndex({ idCliente: 1, 'direccionEnvio.idDireccion': 1 });

print('Migracion aplicada. No se ha modificado ningun otro dato.');
