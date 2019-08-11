package xyz.helioz.heliolaser

import android.opengl.GLES20
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.warn
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HelioGLProgram : AnkoLogger {
    fun openGLSetUniformInt(uniformName: String, value: Int) {
        val location = uniformNameToGLLocation.getValue(uniformName)
        if (location < 0) {
            warn { "OpenGL openGLSetUniformInt not setting compiled out uniform $uniformName at $location to $value" }
            return
        }
        checkedGL { GLES20.glUniform1i(location, value) }
    }

    fun openGLSetUniformFloat(uniformName: String, doubleValue: DoubleArray) {
        val value = FloatArray(doubleValue.size)
        for (i in 0 until value.size) {
            value[i] = doubleValue[i].toFloat()
        }
        openGLSetUniformFloat(uniformName, value)
    }


    fun openGLSetUniformFloat(uniformName: String, value: FloatArray) {
        val location = uniformNameToGLLocation.getValue(uniformName)
        if (location < 0) {
            warn { "OpenGL openGLSetUniformFloat not setting compiled out uniform $uniformName at $location to ${value.toList()}" }
            return
        }
        checkedGL {
            when (value.size) {
                1 -> GLES20.glUniform1f(location, value[0])
                2 -> GLES20.glUniform2f(location, value[0], value[1])
                3 -> GLES20.glUniform3fv(location, 1, value, 0)
                4 -> GLES20.glUniform4fv(location, 1, value, 0)
                9 -> GLES20.glUniformMatrix3fv(location, 1, false, value, 0)
                16 -> GLES20.glUniformMatrix4fv(location, 1, false, value, 0)
                else -> throw UnsupportedOperationException("OpenGL openGLSetUniformFloat not implemented for arrays of size ${value.size}: ${value.toList()}")
            }
        }
    }

    fun openGLTransferFloatsToGPUHandle(doubleVertexArray: DoubleArray): Int {
        val floatVertexArray = FloatArray(doubleVertexArray.size)
        for (i in 0 until floatVertexArray.size) {
            floatVertexArray[i] = doubleVertexArray[i].toFloat()
        }
        return openGLTransferFloatsToGPUHandle(floatVertexArray)
    }

    fun openGLTransferFloatsToGPUHandle(floatVertexArray: FloatArray): Int {
        val buffArray = IntArray(1)
        checkedGL {
            GLES20.glGenBuffers(buffArray.size, buffArray, 0)
        }
        var safelyDone = false
        try {
            checkedGL {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffArray[0])
            }

            // Not using allocateDirect causes memory not be transferred
            // and not using native byte order does not work on ARMv8 64.
            // There is a rumour that FloatBuffer.wrap might be supported and it works but I don't know where that's documented.
            val bytesNeeded = java.lang.Float.BYTES * floatVertexArray.size
            val floatBuffer = ByteBuffer.allocateDirect(bytesNeeded).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatVertexArray)
            floatBuffer.position(0)
            checkedGL {
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bytesNeeded, floatBuffer, GLES20.GL_STATIC_DRAW)
            }
            checkedGL {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            }

            safelyDone = true
            info { "OpenGL openGLTransferFloatsToGPUHandle ${floatVertexArray.toList()} to handle ${buffArray[0]} with size $bytesNeeded bytes"  }

            return buffArray[0]
        } finally {
            if (!safelyDone) {
                checkedGL {
                    GLES20.glDeleteBuffers(buffArray.size, buffArray, 0)
                }
            }
        }
    }

    fun openGLShadeVerticesFromBuffer(attributeName: String, bufferIndex: Int, drawArraysMode:Int, coordsPerVertex: Int, vertexCount: Int) {
        val attribLocation = attributeNameToGLLocation.getValue(attributeName)
        if (attribLocation < 0) {
            warn { "OpenGL openGLShadeVerticesFromBuffer cannot use GL attribute $attributeName which was compiled out" }
            return
        }
        if (bufferIndex == 0) {
            throw IllegalArgumentException("OpenGL openGLShadeVerticesFromBuffer cannot use buffer with 0 index")
        }

        checkedGL {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIndex)
        }
        if (HelioPref("opengl_validate_buffer_bounds", true)) {
            checkedGL {
                val outSize = IntArray(1)
                GLES20.glGetBufferParameteriv(GLES20.GL_ARRAY_BUFFER, GLES20.GL_BUFFER_SIZE, outSize, 0)
                val expectedSize = coordsPerVertex * vertexCount * java.lang.Float.BYTES
                if (outSize[0] < expectedSize) {
                    throw IllegalArgumentException("OpenGL openGLShadeVerticesFromBuffer using buffer $bufferIndex which has size ${outSize[0]} not $expectedSize")
                }
            }
        }
        checkedGL {
            GLES20.glVertexAttribPointer(0, coordsPerVertex, GLES20.GL_FLOAT, false, 0, 0)
        }
        checkedGL {
            GLES20.glEnableVertexAttribArray(attribLocation)
        }

        if (HelioPref("opengl_validate_program", true)) {
            checkedGL {
                GLES20.glValidateProgram(glProgram)
            }
            checkedGL {
                val validateStatusOutput = IntArray(1)
                GLES20.glGetProgramiv(glProgram, GLES20.GL_VALIDATE_STATUS, validateStatusOutput, 0)
                if (GLES20.GL_TRUE != validateStatusOutput[0]) {
                    checkedGL {
                        val glInfo = GLES20.glGetProgramInfoLog(glProgram)
                        if (!glInfo.isNullOrEmpty()) {
                            warn { "OpenGL glGetProgramInfoLog: $glInfo" }
                        }
                    }
                    throw RuntimeException("OpenGL glValidateProgram for $this failed")
                }
            }
        }

        checkedGL {
            GLES20.glDrawArrays(drawArraysMode, 0, vertexCount)
        }
        checkedGL {
            GLES20.glDisableVertexAttribArray(attribLocation)
        }
        checkedGL {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    fun openGLAttachProgram() {
        checkedGL {
            GLES20.glUseProgram(glProgram)
        }
    }

    fun openGLDeleteProgram() {
        tryOrContinue {
            GLES20.glDeleteProgram(glProgram)
            clearGLError()
        }
    }

    private val glProgram: Int = checkedGL {
        GLES20.glCreateProgram()
    }
    val attributeNameToGLLocation = HashMap<String, Int>()
    val uniformNameToGLLocation = HashMap<String, Int>()

    private val uniformMatchRegex = Regex("^[ ]*uniform[ ]+([^ ]+)[ ]+([^ ;]+)[ ]*;", RegexOption.MULTILINE)
    private val attributeMatchRegex = Regex("^[ ]*attribute[ ]+([^ ]+)[ ]+([^ ;]+)[ ]*;", RegexOption.MULTILINE)

    fun openGLCompileAndAttachShader(type: Int, shaderCode: String): Int {
        val shader = checkedGL {
            GLES20.glCreateShader(type)
        }
        checkedGL {
            GLES20.glShaderSource(shader, shaderCode)
        }
        checkedGL {
            GLES20.glCompileShader(shader)
        }

        checkedGL {
            val glInfo = GLES20.glGetShaderInfoLog(shader)
            if (!glInfo.isNullOrEmpty()) {
                warn { "OpenGL glGetShaderInfoLog: $glInfo for shader type $type\n$shaderCode" }
            }
        }

        val compileStatusBuffer = IntArray(1)
        checkedGL {
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatusBuffer, 0)
        }
        if (GLES20.GL_TRUE != compileStatusBuffer[0]) {
            throw RuntimeException("OpenGL glGetShaderiv GL_COMPILE_STATUS failed for shader type $type\n$shaderCode")
        }

        checkedGL {
            GLES20.glAttachShader(glProgram, shader)
        }
        for (match in uniformMatchRegex.findAll(shaderCode)) {
            val (uniformType,name) = match.destructured
            uniformNameToGLLocation[name] = -1
        }
        for (match in attributeMatchRegex.findAll(shaderCode)) {
            val (attributeType,name) = match.destructured
            attributeNameToGLLocation[name] = -1
        }

        return shader
    }

    fun openGLLinkAllShaders() {
        checkedGL {
            GLES20.glLinkProgram(glProgram)
        }
        checkedGL {
            val glInfo = GLES20.glGetProgramInfoLog(glProgram)
            if (!glInfo.isNullOrEmpty()) {
                warn { "OpenGL openGLLinkAllShaders glGetProgramInfoLog: $glInfo" }
            }
        }
        checkedGL {
            val linkStatusOutput = IntArray(1)
            GLES20.glGetProgramiv(glProgram, GLES20.GL_LINK_STATUS, linkStatusOutput, 0)
            if (GLES20.GL_TRUE != linkStatusOutput[0]) {
                throw RuntimeException("OpenGL openGLLinkAllShaders glLinkProgram for $this failed")
            }
        }

        for (name in uniformNameToGLLocation.keys) {
            val location = GLES20.glGetUniformLocation(glProgram, name)
            clearGLError()
            if (location == -1) {
                warn { "OpenGL openGLLinkAllShaders uniform $name was compiled out of the program"}
            } else {
                uniformNameToGLLocation[name] = location
            }

        }
        for (name in attributeNameToGLLocation.keys) {
            val location = GLES20.glGetAttribLocation(glProgram, name)
            clearGLError()
            if (location == -1) {
                warn { "OpenGL openGLLinkAllShaders attribute $name was compiled out of the program" }
            } else {
                attributeNameToGLLocation[name] = location
            }
        }
    }
}
