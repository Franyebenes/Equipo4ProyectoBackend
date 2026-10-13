// Playground de MongoDB para VS Code: muestra los usuarios registrados en la base de datos.
//
// Como usarlo:
//   1. Conecta la extension "MongoDB for VS Code" a mongodb://localhost:27017 (hoja verde de la barra lateral).
//   2. Abre este fichero y pulsa el boton "Play" (o Ctrl+Alt+R). El resultado sale en el panel de la derecha.
//
// Solo lee: no modifica ni borra nada. Tampoco muestra el hash entero de la contrasena, solo su principio.

// --- Ajustes ---------------------------------------------------------------------------------------------

// Base de datos que usa el backend. Si lo arrancas con --spring.mongodb.uri o MONGODB_URI, pon aqui la tuya.
const BASE_DE_DATOS = 'ESIBuy';

// 'lista'   -> los ultimos usuarios registrados, el mas reciente primero.
// 'resumen' -> cuantos usuarios hay por rol y estado.
const MODO = 'lista';

// Para comprobar si alguien concreto se ha registrado, escribe su correo. Vacio = todos.
// Se guarda siempre en minusculas, asi que da igual como lo escribieras en el formulario.
const EMAIL = '';

const MAXIMO_USUARIOS = 50;

// --- Consulta --------------------------------------------------------------------------------------------

use(BASE_DE_DATOS);

const filtro = EMAIL.trim() === '' ? {} : { email: EMAIL.trim().toLowerCase() };

const lista = [
  { $match: filtro },
  { $sort: { _id: -1 } },
  { $limit: MAXIMO_USUARIOS },
  {
    $project: {
      _id: 0,
      // La fecha de alta sale del propio _id: un ObjectId lleva dentro el momento en que se creo.
      registrado: { $toDate: '$_id' },
      email: 1,
      rol: 1,
      estado: 1,
      nombre: '$perfil.nombre',
      apellidos: '$perfil.apellidos',
      dni: '$perfil.dni',
      fechaNacimiento: '$perfil.fechaNacimiento',
      nombreComercial: '$perfil.nombreComercial',
      categoria: '$perfil.idCategoriaPrincipal',
      // Debe empezar por $argon2id$: si ves la contrasena en claro, algo va muy mal.
      hashContrasena: { $concat: [{ $substrCP: [{ $ifNull: ['$password', ''] }, 0, 16] }, '...'] },
    },
  },
];

const resumen = [
  { $match: filtro },
  // Cada usuario tiene un unico rol, guardado en una lista de un elemento.
  { $group: { _id: { rol: { $arrayElemAt: ['$rol', 0] }, estado: '$estado' }, usuarios: { $sum: 1 } } },
  { $sort: { '_id.rol': 1, '_id.estado': 1 } },
];

// La ultima expresion es la que muestra el playground.
db.getCollection('usuarios').aggregate(MODO === 'resumen' ? resumen : lista);
