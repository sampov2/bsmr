/**
 bsmr.js

 BrowserSocket MapReduce
 worker node bootstrap and controller.
 Spawns a Web Worker thread which performs the actual work.
 
 Konrad Markus <konker@gmail.com>

 TODO:
    - stop is not really stopping everything
 */


var bsmr = (function() {
    /* the websocket url of the master to which this worker should use */
    var MASTER_WS_URL = 'ws://127.0.0.1:8080/bsmr/';

    /* debug mode on/off */
    var DEBUG = true;

    /* status codes */
    var STATUS_ERROR    = -2;
    var STATUS_STOPPED  = -1;
    var STATUS_INIT     = 1;
    var STATUS_IDLE     = 2;
    var STATUS_MAPPING  = 3;
    var STATUS_REDUCING = 4;

    /* modes (FIXME: duplication...) */
    var MODE_NOR = 1; // normal mode
    var MODE_TMO = 2; // dummy setTimeout mode
    var MODE_IVE = 3; // user-interactive mode

    return {
        DEBUG: DEBUG,
        MASTER_WS_URL: MASTER_WS_URL,

        /* modes (FIXME: duplication...) */
        MODE_NOR: MODE_NOR,
        MODE_TMO: MODE_TMO,
        MODE_IVE: MODE_IVE,

        /* message types (FIXME: duplication...) */
        TYPE_HB: 'HB',
        TYPE_DO: 'DO',
        TYPE_ACK: 'ACK',
        TYPE_UPL: 'UPL',
        TYPE_LOG: 'LOG',
        TYPE_CTL: 'CTL',

        /* whether to inmmediately start work */
        _autostart: true,

        /* overall status of the worker node */
        status: null,

        curJob: null,
        mapTasks: {},
        reduceTasks: {},

        init: function() {
            if (!bsmr._autostart) {
                bsmr.log('No autoload. aborting init.');
                bsmr._autostart = true;
                return;
            }

            bsmr.status = STATUS_INIT;

            if (("Worker" in window) == false) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Your browser does not seem to support webworkers. Aborting.'
                );
                return;
            }
            if (("WebSocket" in window) == false) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Your browser does not seem to support websockets. Aborting.'
                );
                return;
            }
            if (("BrowserSocket" in window) == false) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Your browser does not seem to support browsersockets. Aborting.'
                );
                return;
            }

            // create a worker thread
            try {
                bsmr.worker.init();
            }
            catch (ex) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Could not create worker thread: ' + + ex
                );
                return;
            }

            // make a ws connection to the master
            try {
                bsmr.master.init();
            }
            catch (ex) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Could not connect to master: ' + MASTER_WS_URL + ': ' + ex
                );
                return;
            }

            // open a bs listener
            try {
                bsmr.incoming.init();
            }
            catch (ex) {
                bsmr.setStatus(
                    STATUS_ERROR,
                    'Could not open browsersocket: ' + ex
                );
            }

        },
        start: function() {
            // use this if _autostart is false
            bsmr.master.greeting();
        },
        step: function() {
            var m = bsmr.createMessage(bsmr.TYPE_CTL, {
                action: 'step'
            });
            bsmr.worker.sendMessage(m);
        },
        idle: function() {
            //[TODO]
            bsmr.log('(Pretend) Idle mode');
        },
        stop: function() {
            bsmr.master.stop();
            bsmr.worker.stop();
            bsmr.incoming.stop();
            bsmr.setStatus(
                STATUS_STOPPED,
                'stopped'
            );
        },

        /*
            web worker thread that does the actual work */
        worker: {
            thread: null,

            init: function() {
                bsmr.worker.thread = new Worker('worker.js');
                bsmr.worker.thread.onmessage = bsmr.worker.onmessage;
            },
            stop: function() {
                bsmr.worker.thread.terminate();
            },

            /* 
                if we receive a message from the worker thread,
                forward it to the master */
            onmessage: function(msg) {
                if (msg.data.type == bsmr.TYPE_ACK) {
                    switch (msg.data.payload.action) {
                        case 'mapTask':
                            //[TODO: do checks and set mapTasks state]
                            bsmr.master.sendMessage(msg.data);
                            break;
                        case 'reduceSplit':
                            //[TODO: do checks and set reduceTasks state]
                            bsmr.master.sendMessage(msg.data);
                            break;
                        case 'reduceTask':
                            //[TODO: do checks and set reduceTasks state]
                            bsmr.master.sendMessage(msg.data);
                            break;
                        default:
                            bsmr.log('Unknown action received from thread: ' + msg.data.payload.action);
                    }
                }
                else if (msg.data.type == bsmr.TYPE_HB) {
                    bsmr.master.sendMessage(msg.data);
                }
                else if (msg.data.type == bsmr.TYPE_LOG) {
                    bsmr.log(msg.data.payload.message, 'log');
                }
            },
            sendMessage: function(msg) {
                bsmr.log(msg, 'm');
                bsmr.worker.thread.postMessage(msg);
            }
        },

        /*
            websockets connection to the master server */
        master: {
            /* the main ws channel to the master */
            ws: null,

            init: function() {
                /*[TODO: should we have a protocol here?] */
                bsmr.master.ws = new WebSocket(MASTER_WS_URL, "worker");
                bsmr.master.ws.onopen = bsmr.master.onopen;
                bsmr.master.ws.onclose = bsmr.master.onclose;
                bsmr.master.ws.onerror = bsmr.master.onerror;
            },
            stop: function() {
                bsmr.master.ws.close();
            },
            greeting: function() {
                try {
                    // send the 'socket' message to master
                    var m = bsmr.createMessage(bsmr.TYPE_ACK, {
                        action: 'socket',
                        protocol: 'ws',
                        port: bsmr.incoming.bs.port,
                        resource: bsmr.incoming.bs.resourcePrefix
                    });
                    bsmr.master.sendMessage(m);

                    // start the worker heartbeat
                    m = bsmr.createMessage(bsmr.TYPE_CTL, {
                        action: 'hb'
                    });
                    bsmr.worker.sendMessage(m);
                }
                catch (ex) {
                    bsmr.setStatus(
                        STATUS_ERROR,
                        'Could not send greeting to master: ' + ex
                    );
                }

                // start accepting incoming messages from the master
                bsmr.master.ws.onmessage = bsmr.master.onmessage;
            },

            /* 
                if we receive a message from the master,
                forward it to the worker thread */
            onmessage: function(e) {
                var m = bsmr.readMessage(e.data);
                if (m.type == bsmr.TYPE_DO) {
                    switch (m.payload.action) {
                        case 'mapTask':
                            //[TODO: do checks and set mapTasks state]
                            bsmr.worker.sendMessage(m);
                            break;
                        case 'reduceTask':
                            //[TODO: do checks and set reduceTasks state]
                            bsmr.worker.sendMessage(m);
                            break;
                        case 'reduceSplit':
                            //[TODO: do checks and set reduceTasks state]
                            bsmr.worker.sendMessage(m);
                            break;
                        case 'idle':
                            bsmr.worker.sendMessage(m);
                            break;
                        default: 
                            // swallow?
                    }
                }
                else {
                    bsmr.log('Ignoring message of type: ' + m.type);
                }
            },
            onopen: function(e) { 
                if (!bsmr._autostart) {
                    bsmr.master.greeting();
                }

                // successful init
                bsmr.setStatus(STATUS_IDLE, 'init complete');
            },
            onclose: function(e) {
                /* FIXME: why does this cause an error? */
                bsmr.log('ws:close');
                bsmr.stop();   
            },
            onerror: function(e) {
                /*[TODO]*/
                bsmr.log('ws:error');
            },
            sendMessage: function(msg) {
                bsmr.log(msg, 'w');
                bsmr.master.ws.send(JSON.stringify(msg));
            }
        },

        /*
            browsersocket connection to other bsmr nodes */
        incoming: {
            /* the browsersocket for communicating with other worker nodes */
            bs: null,

            init: function() {
                bsmr.incoming.bs = new BrowserSocket(bsmr.incoming.handler);
                bsmr.incoming.bs.onerror = bsmr.incoming.onerror;
            },
            stop: function() {
                bsmr.incoming.bs.stop();
            },

            handler: function(req) {
                this.onmessage = function(e) {
                    /*[TODO]*/
                    bsmr.log('bs:handler:onmessage: ' + e.data);
                }
                this.onopen = function(e) { 
                    /*[TODO]*/
                    bsmr.log('bs:handler:open');
                }
                this.onclose = function(e) {
                    /*[TODO]*/
                    bsmr.log('bs:handler:close');
                }
                this.onerror = function(e) {
                    /*[TODO]*/
                    bsmr.log('bs:handler:error');
                }
            },
            onerror: function(e) {
                /*[TODO]*/
                bsmr.log('bs:error');
            }
        },

        /* utils and helpers */
        readMessage: function(s) {
            return JSON.parse(s);
        },
        createMessage: function(type, spec) {
            var ret = {
                payload: {}
            };
            ret.type = type;
            for (var p in spec) {
                if (spec.hasOwnProperty(p)) {
                    ret.payload[p] = spec[p];
                }
            }
            return ret;
        },
        setStatus: function(status, msg) {
            if (msg) {
                bsmr.log(msg);
            }
            bsmr.status = status;
        },
        util: {
            esc: function(s) {
                return s.replace(/&/g, '&amp;')
                        .replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;');
            }
        },
        log: function(s, level) {
            if (bsmr.DEBUG) {
                if (typeof(console) != 'undefined' && typeof(console.log) == 'function') {
                    console.log(s);
                }
            }
        }
    }
})();
window.addEventListener('load', bsmr.init, false);

