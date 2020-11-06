/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.common.overrides.FakeOverrideBuilder
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.jetbrains.kotlin.backend.common.serialization.encodings.*
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration.DeclaratorCase.*
import org.jetbrains.kotlin.backend.common.serialization.proto.IrType.KindCase.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrErrorExpressionImpl
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.IrPublicSymbolBase
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.backend.common.serialization.proto.IrAnonymousInit as ProtoAnonymousInit
import org.jetbrains.kotlin.backend.common.serialization.proto.IrClass as ProtoClass
import org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructor as ProtoConstructor
import org.jetbrains.kotlin.backend.common.serialization.proto.IrConstructorCall as ProtoConstructorCall
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration as ProtoDeclaration
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase as ProtoDeclarationBase
import org.jetbrains.kotlin.backend.common.serialization.proto.IrDynamicType as ProtoDynamicType
import org.jetbrains.kotlin.backend.common.serialization.proto.IrEnumEntry as ProtoEnumEntry
import org.jetbrains.kotlin.backend.common.serialization.proto.IrErrorType as ProtoErrorType
import org.jetbrains.kotlin.backend.common.serialization.proto.IrField as ProtoField
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFunction as ProtoFunction
import org.jetbrains.kotlin.backend.common.serialization.proto.IrFunctionBase as ProtoFunctionBase
import org.jetbrains.kotlin.backend.common.serialization.proto.IrErrorDeclaration as ProtoErrorDeclaration
import org.jetbrains.kotlin.backend.common.serialization.proto.IrLocalDelegatedProperty as ProtoLocalDelegatedProperty
import org.jetbrains.kotlin.backend.common.serialization.proto.IrProperty as ProtoProperty
import org.jetbrains.kotlin.backend.common.serialization.proto.IrSimpleType as ProtoSimpleType
import org.jetbrains.kotlin.backend.common.serialization.proto.IrType as ProtoType
import org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeAbbreviation as ProtoTypeAbbreviation
import org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeAlias as ProtoTypeAlias
import org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter as ProtoTypeParameter
import org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter as ProtoValueParameter
import org.jetbrains.kotlin.backend.common.serialization.proto.IrVariable as ProtoVariable


internal class IrDeclarationDeserializer(
    val logger: LoggingContext,
    val builtIns: IrBuiltIns,
    val symbolTable: SymbolTable,
    val irFactory: IrFactory,
    val deserializeFakeOverrides: Boolean,
    val fakeOverrideQueue: MutableList<IrClass>,
    val allowErrorNodes: Boolean,
    val deserializeInlineFunctions: Boolean,
    protected var deserializeBodies: Boolean,
    fakeOverrideBuilder: FakeOverrideBuilder,
    val fileDeserializer: IrFileDeserializer,
) {

    private val bodyDeserializer = IrBodyDeserializer(logger, builtIns, allowErrorNodes, irFactory, fileDeserializer, this)

    private val platformFakeOverrideClassFilter = fakeOverrideBuilder.platformSpecificClassFilter

    private fun deserializeName(index: Int): Name {
        val name = fileDeserializer.deserializeString(index)
        return Name.guessByFirstCharacter(name)
    }

    private val irTypeCache = mutableMapOf<Int, IrType>()

    fun deserializeIrType(index: Int): IrType {
        return irTypeCache.getOrPut(index) {
            val typeData = fileDeserializer.loadTypeProto(index)
            deserializeIrTypeData(typeData)
        }
    }

    private fun deserializeIrTypeArgument(proto: Long): IrTypeArgument {
        val encoding = BinaryTypeProjection.decode(proto)

        if (encoding.isStarProjection) return IrStarProjectionImpl

        return makeTypeProjection(deserializeIrType(encoding.typeIndex), encoding.variance)
    }

    fun deserializeAnnotations(annotations: List<ProtoConstructorCall>): List<IrConstructorCall> {
        return annotations.map {
            bodyDeserializer.deserializeConstructorCall(it, 0, 0, builtIns.unitType) // TODO: need a proper deserialization here
        }
    }

    private fun deserializeSimpleType(proto: ProtoSimpleType): IrSimpleType {
        val symbol = fileDeserializer.deserializeIrSymbolAndRemap(proto.classifier) as? IrClassifierSymbol
            ?: error("could not convert sym to ClassifierSymbol")
        logger.log { "deserializeSimpleType: symbol=$symbol" }

        val arguments = proto.argumentList.map { deserializeIrTypeArgument(it) }
        val annotations = deserializeAnnotations(proto.annotationList)

        val result: IrSimpleType = IrSimpleTypeImpl(
            null,
            symbol,
            proto.hasQuestionMark,
            arguments,
            annotations,
            if (proto.hasAbbreviation()) deserializeTypeAbbreviation(proto.abbreviation) else null
        )
        logger.log { "ir_type = $result; render = ${result.render()}" }
        return result

    }

    private fun deserializeTypeAbbreviation(proto: ProtoTypeAbbreviation): IrTypeAbbreviation =
        IrTypeAbbreviationImpl(
            fileDeserializer.deserializeIrSymbolAndRemap(proto.typeAlias).let {
                it as? IrTypeAliasSymbol
                    ?: error("IrTypeAliasSymbol expected: $it")
            },
            proto.hasQuestionMark,
            proto.argumentList.map { deserializeIrTypeArgument(it) },
            deserializeAnnotations(proto.annotationList)
        )

    private fun deserializeDynamicType(proto: ProtoDynamicType): IrDynamicType {
        val annotations = deserializeAnnotations(proto.annotationList)
        return IrDynamicTypeImpl(null, annotations, Variance.INVARIANT)
    }

    private fun deserializeErrorType(proto: ProtoErrorType): IrErrorType {
        require(allowErrorNodes) { "IrErrorType found but error code is not allowed" }
        val annotations = deserializeAnnotations(proto.annotationList)
        return IrErrorTypeImpl(null, annotations, Variance.INVARIANT)
    }

    fun deserializeIrTypeData(proto: ProtoType): IrType {
        return when (proto.kindCase) {
            SIMPLE -> deserializeSimpleType(proto.simple)
            DYNAMIC -> deserializeDynamicType(proto.dynamic)
            ERROR -> deserializeErrorType(proto.error)
            else -> error("Unexpected IrType kind: ${proto.kindCase}")
        }
    }

    private val parentsStack = mutableListOf<IrDeclarationParent>()

    private inline fun <T : IrDeclarationParent, R> usingParent(parent: T, block: (T) -> R): R {
        parentsStack.push(parent)
        try {
            return block(parent)
        } finally {
            parentsStack.pop()
        }
    }

    private inline fun <T : IrDeclarationParent> T.usingParent(block: T.() -> Unit): T =
        this.apply { usingParent(this) { block(it) } }

    private inline fun <T> withDeserializedIrDeclarationBase(
        proto: ProtoDeclarationBase,
        block: (IrSymbol, IdSignature, Int, Int, IrDeclarationOrigin, Long) -> T
    ): T where T : IrDeclaration, T : IrSymbolOwner {
        val (s, uid) = fileDeserializer.deserializeIrSymbolToDeclare(proto.symbol)
        val coordinates = BinaryCoordinates.decode(proto.coordinates)
        try {
            fileDeserializer.recordDelegatedSymbol(s)
            val result = block(
                s,
                uid,
                coordinates.startOffset, coordinates.endOffset,
                deserializeIrDeclarationOrigin(proto.originName), proto.flags
            )
            result.annotations += deserializeAnnotations(proto.annotationList)
            result.parent = parentsStack.peek()!!
            return result
        } finally {
            fileDeserializer.eraseDelegatedSymbol(s)
        }
    }

    private fun deserializeIrTypeParameter(proto: ProtoTypeParameter, index: Int, isGlobal: Boolean): IrTypeParameter {
        val name = deserializeName(proto.name)
        val coordinates = BinaryCoordinates.decode(proto.base.coordinates)
        val flags = TypeParameterFlags.decode(proto.base.flags)

        val factory = { symbol: IrTypeParameterSymbol ->
            irFactory.createTypeParameter(
                coordinates.startOffset,
                coordinates.endOffset,
                deserializeIrDeclarationOrigin(proto.base.originName),
                symbol,
                name,
                index,
                flags.isReified,
                flags.variance
            )
        }

        val sig: IdSignature
        val result = symbolTable.run {
            if (isGlobal) {
                val p = fileDeserializer.deserializeIrSymbolToDeclare(proto.base.symbol)
                val symbol = p.first
                sig = p.second
                val descriptor = (symbol as IrTypeParameterSymbol).descriptor
                declareGlobalTypeParameterFromLinker(descriptor, sig, factory)
            } else {
                val symbolData = BinarySymbolData
                    .decode(proto.base.symbol)
                sig = fileDeserializer.deserializeIdSignature(symbolData.signatureId)
                val descriptor = WrappedTypeParameterDescriptor()
                declareScopedTypeParameterFromLinker(descriptor, sig, factory)
            }
        }

        (result.descriptor as? WrappedTypeParameterDescriptor)?.bind(result)

        // make sure this symbol is known to linker
        fileDeserializer.referenceIrSymbol(result.symbol, sig)
        result.annotations += deserializeAnnotations(proto.base.annotationList)
        result.parent = parentsStack.peek()!!
        return result
    }

    private fun deserializeIrValueParameter(proto: ProtoValueParameter, index: Int): IrValueParameter =
        withDeserializedIrDeclarationBase(proto.base) { symbol, _, startOffset, endOffset, origin, fcode ->
            val flags = ValueParameterFlags.decode(fcode)
            val nameAndType = BinaryNameAndType.decode(proto.nameType)
            irFactory.createValueParameter(
                startOffset, endOffset, origin,
                symbol as IrValueParameterSymbol,
                deserializeName(nameAndType.nameIndex),
                index,
                deserializeIrType(nameAndType.typeIndex),
                if (proto.hasVarargElementType()) deserializeIrType(proto.varargElementType) else null,
                flags.isCrossInline,
                flags.isNoInline,
                flags.isHidden,
                flags.isAssignable
            ).apply {
                if (proto.hasDefaultValue())
                    defaultValue = irFactory.createExpressionBody(deserializeExpressionBody(proto.defaultValue))

                (descriptor as? WrappedValueParameterDescriptor)?.bind(this)
                (descriptor as? WrappedReceiverParameterDescriptor)?.bind(this)
            }
        }

    private fun deserializeIrClass(proto: ProtoClass): IrClass =
        withDeserializedIrDeclarationBase(proto.base) { symbol, signature, startOffset, endOffset, origin, fcode ->
            val flags = ClassFlags.decode(fcode)

            symbolTable.declareClassFromLinker((symbol as IrClassSymbol).descriptor, signature) {
                irFactory.createClass(
                    startOffset, endOffset, origin,
                    it,
                    deserializeName(proto.name),
                    flags.kind,
                    flags.visibility,
                    flags.modality,
                    flags.isCompanion,
                    flags.isInner,
                    flags.isData,
                    flags.isExternal,
                    flags.isInline,
                    flags.isExpect,
                    flags.isFun,
                )
            }.usingParent {
                typeParameters = deserializeTypeParameters(proto.typeParameterList, true)

                superTypes = proto.superTypeList.map { deserializeIrType(it) }

                proto.declarationList
                    .filterNot { isSkippableFakeOverride(it, this) }
                    .mapTo(declarations) { deserializeDeclaration(it) }

                thisReceiver = deserializeIrValueParameter(proto.thisReceiver, -1)

                (descriptor as? WrappedClassDescriptor)?.bind(this)

                if (!deserializeFakeOverrides) {
                    if (symbol.isPublicApi) {
                        fakeOverrideQueue.add(this)
                    }
                }
            }
        }

    private fun deserializeIrTypeAlias(proto: ProtoTypeAlias): IrTypeAlias =
        withDeserializedIrDeclarationBase(proto.base) { symbol, uniqId, startOffset, endOffset, origin, fcode ->
            symbolTable.declareTypeAliasFromLinker((symbol as IrTypeAliasSymbol).descriptor, uniqId) {
                val flags = TypeAliasFlags.decode(fcode)
                val nameType = BinaryNameAndType.decode(proto.nameType)
                irFactory.createTypeAlias(
                    startOffset, endOffset,
                    it,
                    deserializeName(nameType.nameIndex),
                    flags.visibility,
                    deserializeIrType(nameType.typeIndex),
                    flags.isActual,
                    origin
                )
            }.usingParent {
                typeParameters = deserializeTypeParameters(proto.typeParameterList, true)

                (descriptor as? WrappedTypeAliasDescriptor)?.bind(this)
            }
        }

    private fun deserializeErrorDeclaration(proto: ProtoErrorDeclaration): IrErrorDeclaration {
        require(allowErrorNodes) { "IrErrorDeclaration found but error code is not allowed" }
        val coordinates = BinaryCoordinates.decode(proto.coordinates)
        val descriptor = WrappedErrorDescriptor()
        return irFactory.createErrorDeclaration(coordinates.startOffset, coordinates.endOffset, descriptor).also {
            descriptor.bind(it)
            it.parent = parentsStack.peek()!!
        }
    }

    private fun deserializeTypeParameters(protos: List<ProtoTypeParameter>, isGlobal: Boolean): List<IrTypeParameter> {
        // NOTE: fun <C : MutableCollection<in T>, T : Any> Array<out T?>.filterNotNullTo(destination: C): C
        val result = ArrayList<IrTypeParameter>(protos.size)
        for (index in protos.indices) {
            val proto = protos[index]
            result.add(deserializeIrTypeParameter(proto, index, isGlobal))
        }

        for (i in protos.indices) {
            result[i].superTypes = protos[i].superTypeList.map { deserializeIrType(it) }
        }

        return result
    }

    private fun deserializeValueParameters(protos: List<ProtoValueParameter>): List<IrValueParameter> {
        val result = ArrayList<IrValueParameter>(protos.size)

        for (i in protos.indices) {
            result.add(deserializeIrValueParameter(protos[i], i))
        }

        return result
    }


    /**
     * In `declarations-only` mode in case of private property/function with inferred anonymous private type like this
     * class C {
     *   private val p = object {
     *     fun foo() = 42
     *   }
     *
     *   private fun f() = object {
     *     fun bar() = "42"
     *   }
     *
     *   private val pp = p.foo()
     *   private fun ff() = f().bar()
     * }
     * object's classifier is leaked outside p/f scopes and accessible on C's level so
     * if their initializer/body weren't read we have unbound `foo/bar` symbol and unbound `object` symbols.
     * To fix this make sure that such declaration forced to be deserialized completely.
     *
     * For more information see `anonymousClassLeak.kt` test and issue KT-40216
     */
    private fun IrType.checkObjectLeak(isPrivate: Boolean): Boolean {
        return isPrivate && this is IrSimpleType && classifier.let { !it.isPublicApi && it !is IrTypeParameterSymbol }
    }

    private fun <T : IrFunction> T.withBodyGuard(block: T.() -> Unit) {
        val oldBodiesPolicy = deserializeBodies

        fun checkInlineBody(): Boolean = deserializeInlineFunctions && this is IrSimpleFunction && isInline

        try {
            deserializeBodies = oldBodiesPolicy || checkInlineBody() || returnType.checkObjectLeak(!symbol.isPublicApi)
            block()
        } finally {
            deserializeBodies = oldBodiesPolicy
        }
    }


    private fun IrField.withInitializerGuard(isPrivateProperty: Boolean, f: IrField.() -> Unit) {
        val oldBodiesPolicy = deserializeBodies

        try {
            deserializeBodies = oldBodiesPolicy || type.checkObjectLeak(isPrivateProperty)
            f()
        } finally {
            deserializeBodies = oldBodiesPolicy
        }
    }

    fun deserializeExpressionBody(index: Int): IrExpression {
        return if (deserializeBodies) {
            val bodyData = fileDeserializer.loadExpressionBodyProto(index)
            bodyDeserializer.deserializeExpression(bodyData)
        } else {
            val errorType = IrErrorTypeImpl(null, emptyList(), Variance.INVARIANT)
            IrErrorExpressionImpl(-1, -1, errorType, "Expression body is not deserialized yet")
        }
    }

    fun deserializeStatementBody(index: Int): IrElement {
        return if (deserializeBodies) {
            val bodyData = fileDeserializer.loadStatementBodyProto(index)
            bodyDeserializer.deserializeStatement(bodyData)
        } else {
            val errorType = IrErrorTypeImpl(null, emptyList(), Variance.INVARIANT)
            irFactory.createBlockBody(
                -1, -1, listOf(IrErrorExpressionImpl(-1, -1, errorType, "Statement body is not deserialized yet"))
            )
        }
    }

    private inline fun <T : IrFunction> withDeserializedIrFunctionBase(
        proto: ProtoFunctionBase,
        block: (IrFunctionSymbol, IdSignature, Int, Int, IrDeclarationOrigin, Long) -> T
    ): T = withDeserializedIrDeclarationBase(proto.base) { symbol, idSig, startOffset, endOffset, origin, fcode ->
        symbolTable.withScope(symbol.descriptor) {
            block(symbol as IrFunctionSymbol, idSig, startOffset, endOffset, origin, fcode).usingParent {
                typeParameters = deserializeTypeParameters(proto.typeParameterList, false)
                val nameType = BinaryNameAndType.decode(proto.nameType)
                returnType = deserializeIrType(nameType.typeIndex)

                withBodyGuard {
                    valueParameters = deserializeValueParameters(proto.valueParameterList)
                    if (proto.hasDispatchReceiver())
                        dispatchReceiverParameter = deserializeIrValueParameter(proto.dispatchReceiver, -1)
                    if (proto.hasExtensionReceiver())
                        extensionReceiverParameter = deserializeIrValueParameter(proto.extensionReceiver, -1)
                    if (proto.hasBody()) {
                        body = deserializeStatementBody(proto.body) as IrBody
                    }
                }
            }
        }
    }

    internal fun deserializeIrFunction(proto: ProtoFunction): IrSimpleFunction {
        return withDeserializedIrFunctionBase(proto.base) { symbol, idSig, startOffset, endOffset, origin, fcode ->
            val flags = FunctionFlags.decode(fcode)
            symbolTable.declareSimpleFunctionFromLinker(symbol.descriptor, idSig) {
                val nameType = BinaryNameAndType.decode(proto.base.nameType)
                irFactory.createFunction(
                    startOffset, endOffset, origin,
                    it,
                    deserializeName(nameType.nameIndex),
                    flags.visibility,
                    flags.modality,
                    IrUninitializedType,
                    flags.isInline,
                    flags.isExternal,
                    flags.isTailrec,
                    flags.isSuspend,
                    flags.isOperator,
                    flags.isInfix,
                    flags.isExpect,
                    flags.isFakeOverride
                )
            }.apply {
                overriddenSymbols = proto.overriddenList.map { fileDeserializer.deserializeIrSymbolAndRemap(it) as IrSimpleFunctionSymbol }

                (descriptor as? WrappedSimpleFunctionDescriptor)?.bind(this)
            }
        }
    }

    internal fun deserializeIrVariable(proto: ProtoVariable): IrVariable =
        withDeserializedIrDeclarationBase(proto.base) { symbol, _, startOffset, endOffset, origin, fcode ->
            val flags = LocalVariableFlags.decode(fcode)
            val nameType = BinaryNameAndType.decode(proto.nameType)
            IrVariableImpl(
                startOffset, endOffset, origin,
                symbol as IrVariableSymbol,
                deserializeName(nameType.nameIndex),
                deserializeIrType(nameType.typeIndex),
                flags.isVar,
                flags.isConst,
                flags.isLateinit
            ).apply {
                if (proto.hasInitializer())
                    initializer = bodyDeserializer.deserializeExpression(proto.initializer)

                (descriptor as? WrappedVariableDescriptor)?.bind(this)
            }
        }

    private fun deserializeIrEnumEntry(proto: ProtoEnumEntry): IrEnumEntry =
        withDeserializedIrDeclarationBase(proto.base) { symbol, uniqId, startOffset, endOffset, origin, _ ->
            symbolTable.declareEnumEntryFromLinker((symbol as IrEnumEntrySymbol).descriptor, uniqId) {
                irFactory.createEnumEntry(startOffset, endOffset, origin, it, deserializeName(proto.name))
            }.apply {
                if (proto.hasCorrespondingClass())
                    correspondingClass = deserializeIrClass(proto.correspondingClass)
                if (proto.hasInitializer())
                    initializerExpression = irFactory.createExpressionBody(deserializeExpressionBody(proto.initializer))

                (descriptor as? WrappedEnumEntryDescriptor)?.bind(this)
            }
        }

    private fun deserializeIrAnonymousInit(proto: ProtoAnonymousInit): IrAnonymousInitializer =
        withDeserializedIrDeclarationBase(proto.base) { symbol, _, startOffset, endOffset, origin, _ ->
            irFactory.createAnonymousInitializer(startOffset, endOffset, origin, symbol as IrAnonymousInitializerSymbol).apply {
//                body = deserializeBlockBody(proto.body.blockBody, startOffset, endOffset)
                body = deserializeStatementBody(proto.body) as IrBlockBody

                (descriptor as? WrappedClassDescriptor)?.bind(parentsStack.peek() as IrClass)
            }
        }

    private fun deserializeIrConstructor(proto: ProtoConstructor): IrConstructor =
        withDeserializedIrFunctionBase(proto.base) { symbol, idSig, startOffset, endOffset, origin, fcode ->
            val flags = FunctionFlags.decode(fcode)
            val nameType = BinaryNameAndType.decode(proto.base.nameType)
            symbolTable.declareConstructorFromLinker((symbol as IrConstructorSymbol).descriptor, idSig) {
                irFactory.createConstructor(
                    startOffset, endOffset, origin,
                    it,
                    deserializeName(nameType.nameIndex),
                    flags.visibility,
                    IrUninitializedType,
                    flags.isInline,
                    flags.isExternal,
                    flags.isPrimary,
                    flags.isExpect
                )
            }.apply {
                (descriptor as? WrappedClassConstructorDescriptor)?.bind(this)
            }
        }


    private fun deserializeIrField(proto: ProtoField, isPrivateProperty: Boolean): IrField =
        withDeserializedIrDeclarationBase(proto.base) { symbol, uniqId, startOffset, endOffset, origin, fcode ->
            val nameType = BinaryNameAndType.decode(proto.nameType)
            val type = deserializeIrType(nameType.typeIndex)
            val flags = FieldFlags.decode(fcode)
            symbolTable.declareFieldFromLinker((symbol as IrFieldSymbol).descriptor, uniqId) {
                irFactory.createField(
                    startOffset, endOffset, origin,
                    it,
                    deserializeName(nameType.nameIndex),
                    type,
                    flags.visibility,
                    flags.isFinal,
                    flags.isExternal,
                    flags.isStatic,
                )
            }.usingParent {
                if (proto.hasInitializer()) {
                    withInitializerGuard(isPrivateProperty) {
                        initializer = irFactory.createExpressionBody(deserializeExpressionBody(proto.initializer))
                    }
                }

                (descriptor as? WrappedFieldDescriptor)?.bind(this)
            }
        }

    private fun deserializeIrLocalDelegatedProperty(proto: ProtoLocalDelegatedProperty): IrLocalDelegatedProperty =
        withDeserializedIrDeclarationBase(proto.base) { symbol, _, startOffset, endOffset, origin, fcode ->
            val flags = LocalVariableFlags.decode(fcode)
            val nameAndType = BinaryNameAndType.decode(proto.nameType)
            irFactory.createLocalDelegatedProperty(
                startOffset, endOffset, origin,
                symbol as IrLocalDelegatedPropertySymbol,
                deserializeName(nameAndType.nameIndex),
                deserializeIrType(nameAndType.typeIndex),
                flags.isVar
            ).apply {
                delegate = deserializeIrVariable(proto.delegate)
                getter = deserializeIrFunction(proto.getter)
                if (proto.hasSetter())
                    setter = deserializeIrFunction(proto.setter)

                (descriptor as? WrappedVariableDescriptorWithAccessor)?.bind(this)
            }
        }

    private fun deserializeIrProperty(proto: ProtoProperty): IrProperty =
        withDeserializedIrDeclarationBase(proto.base) { symbol, uniqId, startOffset, endOffset, origin, fcode ->
            val flags = PropertyFlags.decode(fcode)
            symbolTable.declarePropertyFromLinker((symbol as IrPropertySymbol).descriptor, uniqId) {
                irFactory.createProperty(
                    startOffset, endOffset, origin,
                    it,
                    deserializeName(proto.name),
                    flags.visibility,
                    flags.modality,
                    flags.isVar,
                    flags.isConst,
                    flags.isLateinit,
                    flags.isDelegated,
                    flags.isExternal,
                    flags.isExpect,
                    flags.isFakeOverride
                )
            }.apply {
                if (proto.hasGetter()) {
                    getter = deserializeIrFunction(proto.getter).also {
                        it.correspondingPropertySymbol = symbol
                    }
                }
                if (proto.hasSetter()) {
                    setter = deserializeIrFunction(proto.setter).also {
                        it.correspondingPropertySymbol = symbol
                    }
                }
                if (proto.hasBackingField()) {
                    backingField = deserializeIrField(proto.backingField, !symbol.isPublicApi).also {
                        // A property symbol and its field symbol share the same descriptor.
                        // Unfortunately symbol deserialization doesn't know anything about that.
                        // So we can end up with two wrapped property descriptors for property and its field.
                        // In that case we need to bind the field's one here.
                        if (descriptor != it.descriptor)
                            (it.descriptor as? WrappedPropertyDescriptor)?.bind(this)
                        it.correspondingPropertySymbol = symbol
                    }
                }

                (descriptor as? WrappedPropertyDescriptor)?.bind(this)
            }
        }

    companion object {
        private val allKnownDeclarationOrigins =
            IrDeclarationOrigin::class.nestedClasses.toList() + InnerClassesSupport.FIELD_FOR_OUTER_THIS::class
        private val declarationOriginIndex =
            allKnownDeclarationOrigins.map { it.objectInstance as IrDeclarationOriginImpl }.associateBy { it.name }
    }

    private fun deserializeIrDeclarationOrigin(protoName: Int): IrDeclarationOriginImpl {
        val originName = fileDeserializer.deserializeString(protoName)
        return declarationOriginIndex[originName] ?: object : IrDeclarationOriginImpl(originName) {}
    }

    internal fun deserializeDeclaration(proto: ProtoDeclaration): IrDeclaration {
        val declaration: IrDeclaration = when (proto.declaratorCase!!) {
            IR_ANONYMOUS_INIT -> deserializeIrAnonymousInit(proto.irAnonymousInit)
            IR_CONSTRUCTOR -> deserializeIrConstructor(proto.irConstructor)
            IR_FIELD -> deserializeIrField(proto.irField, false)
            IR_CLASS -> deserializeIrClass(proto.irClass)
            IR_FUNCTION -> deserializeIrFunction(proto.irFunction)
            IR_PROPERTY -> deserializeIrProperty(proto.irProperty)
            IR_TYPE_PARAMETER -> error("Unreachable execution Type Parameter") // deserializeIrTypeParameter(proto.irTypeParameter)
            IR_VARIABLE -> deserializeIrVariable(proto.irVariable)
            IR_VALUE_PARAMETER -> error("Unreachable execution Value Parameter") // deserializeIrValueParameter(proto.irValueParameter)
            IR_ENUM_ENTRY -> deserializeIrEnumEntry(proto.irEnumEntry)
            IR_LOCAL_DELEGATED_PROPERTY -> deserializeIrLocalDelegatedProperty(proto.irLocalDelegatedProperty)
            IR_TYPE_ALIAS -> deserializeIrTypeAlias(proto.irTypeAlias)
            IR_ERROR_DECLARATION -> deserializeErrorDeclaration(proto.irErrorDeclaration)
            DECLARATOR_NOT_SET -> error("Declaration deserialization not implemented: ${proto.declaratorCase}")
        }

        logger.log { "### Deserialized declaration: ${declaration.descriptor} -> ${ir2string(declaration)}" }

        return declaration
    }

    // Depending on deserialization strategy we either deserialize public api fake overrides
    // or reconstruct them after IR linker completes.
    private fun isSkippableFakeOverride(proto: ProtoDeclaration, parent: IrClass): Boolean {
        if (deserializeFakeOverrides) return false
        if (!platformFakeOverrideClassFilter.constructFakeOverrides(parent)) return false

        val symbol = when (proto.declaratorCase!!) {
            IR_FUNCTION -> fileDeserializer.deserializeIrSymbol(proto.irFunction.base.base.symbol)
            IR_PROPERTY -> fileDeserializer.deserializeIrSymbol(proto.irProperty.base.symbol)
            // Don't consider IR_FIELDS here.
            else -> return false
        }
        if (symbol !is IrPublicSymbolBase<*>) return false
        if (!symbol.signature.isPublic) return false

        return when (proto.declaratorCase!!) {
            IR_FUNCTION -> FunctionFlags.decode(proto.irFunction.base.base.flags).isFakeOverride
            IR_PROPERTY -> PropertyFlags.decode(proto.irProperty.base.flags).isFakeOverride
            // Don't consider IR_FIELDS here.
            else -> false
        }
    }

    fun deserializeDeclaration(proto: ProtoDeclaration, parent: IrDeclarationParent): IrDeclaration =
        usingParent(parent) {
            deserializeDeclaration(proto)
        }
}