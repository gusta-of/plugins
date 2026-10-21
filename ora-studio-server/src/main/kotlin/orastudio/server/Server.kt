package orastudio.server

import kotlin.system.exitProcess

/**
 * Servidor JSON-RPC (uma mensagem JSON por linha, em stdin/stdout) usado pela extensao do VS Code.
 * O stdout e so para o protocolo: qualquer System.out acidental e redirigido para o stderr.
 */
fun main() {
    val protocolOut = System.out
    System.setOut(System.err)
    orastudio.ConfigStore.load()
    RpcServer(System.`in`, protocolOut).serve()
    exitProcess(0)
}
