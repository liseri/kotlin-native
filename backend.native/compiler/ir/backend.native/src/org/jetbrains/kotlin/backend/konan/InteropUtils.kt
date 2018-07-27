/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.descriptors.ClassifierAliasingPackageFragmentDescriptor
import org.jetbrains.kotlin.backend.konan.descriptors.ExportedForwardDeclarationsPackageFragmentDescriptor
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.backend.konan.serialization.KonanPackageFragment
import org.jetbrains.kotlin.builtins.UnsignedType
import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.konan.interop.InteropFqNames
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.util.OperatorNameConventions

interface InteropLibrary {
    fun createSyntheticPackages(
            module: ModuleDescriptor,
            konanPackageFragments: List<KonanPackageFragment>
    ): List<PackageFragmentDescriptor>
}

fun createInteropLibrary(reader: KonanLibraryReader): InteropLibrary? {
    if (reader.manifestProperties.getProperty("interop") != "true") return null
    val pkg = reader.manifestProperties.getProperty("package") 
        ?: error("Inconsistent manifest: interop library ${reader.libraryName} should have `package` specified")
    val exportForwardDeclarations = reader.manifestProperties
            .getProperty("exportForwardDeclarations").split(' ')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { FqName(it) }

    return InteropLibraryImpl(FqName(pkg), exportForwardDeclarations)
}

internal class InteropBuiltIns(builtIns: KonanBuiltIns, vararg konanPrimitives: ClassDescriptor) {

    val packageScope = builtIns.builtInsModule.getPackage(InteropFqNames.packageName).memberScope

    val getPointerSize = packageScope.getContributedFunctions("getPointerSize").single()

    val nativePointed = packageScope.getContributedClass(InteropFqNames.nativePointedName)

    val cPointer = this.packageScope.getContributedClass(InteropFqNames.cPointerName)

    val cPointerRawValue = cPointer.unsubstitutedMemberScope.getContributedVariables("rawValue").single()

    val cPointerGetRawValue = packageScope.getContributedFunctions("getRawValue").single {
        val extensionReceiverParameter = it.extensionReceiverParameter
        extensionReceiverParameter != null &&
                TypeUtils.getClassDescriptor(extensionReceiverParameter.type) == cPointer
    }

    val nativePointedRawPtrGetter =
            nativePointed.unsubstitutedMemberScope.getContributedVariables("rawPtr").single().getter!!

    val nativePointedGetRawPointer = packageScope.getContributedFunctions("getRawPointer").single {
        val extensionReceiverParameter = it.extensionReceiverParameter
        extensionReceiverParameter != null &&
                TypeUtils.getClassDescriptor(extensionReceiverParameter.type) == nativePointed
    }

    val interpretNullablePointed = packageScope.getContributedFunctions("interpretNullablePointed").single()

    val interpretCPointer = packageScope.getContributedFunctions("interpretCPointer").single()

    val typeOf = packageScope.getContributedFunctions("typeOf").single()

    val nativeMemUtils = packageScope.getContributedClass("nativeMemUtils")

    private val primitives = arrayOf(
            arrayOf(builtIns.byte, builtIns.short, builtIns.int, builtIns.long, builtIns.float, builtIns.double),
            konanPrimitives).flatten()

    val readPrimitive = primitives.map {
        nativeMemUtils.unsubstitutedMemberScope.getContributedFunctions("get" + it.name).single()
    }.toSet()

    val writePrimitive = primitives.map {
        nativeMemUtils.unsubstitutedMemberScope.getContributedFunctions("put" + it.name).single()
    }.toSet()

    val bitsToFloat = packageScope.getContributedFunctions("bitsToFloat").single()

    val bitsToDouble = packageScope.getContributedFunctions("bitsToDouble").single()

    val staticCFunction = packageScope.getContributedFunctions("staticCFunction").toSet()

    val workerPackageScope = builtIns.builtInsModule.getPackage(FqName("kotlin.native.worker")).memberScope

    val scheduleFunction = workerPackageScope.getContributedClass("Worker")
            .unsubstitutedMemberScope.getContributedFunctions("schedule").single()

    val scheduleImplFunction = workerPackageScope.getContributedFunctions("scheduleImpl").single()

    val signExtend = packageScope.getContributedFunctions("signExtend").single()

    val narrow = packageScope.getContributedFunctions("narrow").single()

    val convert = packageScope.getContributedFunctions("convert").toSet()

    val readBits = packageScope.getContributedFunctions("readBits").single()
    val writeBits = packageScope.getContributedFunctions("writeBits").single()

    val cFunctionPointerInvokes = packageScope.getContributedFunctions(OperatorNameConventions.INVOKE.asString())
            .filter {
                val extensionReceiverParameter = it.extensionReceiverParameter
                it.isOperator &&
                        extensionReceiverParameter != null &&
                        TypeUtils.getClassDescriptor(extensionReceiverParameter.type) == cPointer
            }.toSet()

    private fun KonanBuiltIns.getUnsignedClass(unsignedType: UnsignedType): ClassDescriptor =
            this.builtInsModule.findClassAcrossModuleDependencies(unsignedType.classId)!!

    val invokeImpls = mapOf(
            builtIns.unit to "invokeImplUnitRet",
            builtIns.boolean to "invokeImplBooleanRet",
            builtIns.byte to "invokeImplByteRet",
            builtIns.short to "invokeImplShortRet",
            builtIns.int to "invokeImplIntRet",
            builtIns.long to "invokeImplLongRet",
            builtIns.getUnsignedClass(UnsignedType.UBYTE) to "invokeImplUByteRet",
            builtIns.getUnsignedClass(UnsignedType.USHORT) to "invokeImplUShortRet",
            builtIns.getUnsignedClass(UnsignedType.UINT) to "invokeImplUIntRet",
            builtIns.getUnsignedClass(UnsignedType.ULONG) to "invokeImplULongRet",
            builtIns.float to "invokeImplFloatRet",
            builtIns.double to "invokeImplDoubleRet",
            cPointer to "invokeImplPointerRet"
    ).mapValues { (_, name) ->
        packageScope.getContributedFunctions(name).single()
    }.toMap()

    val objCObject = packageScope.getContributedClass("ObjCObject")

    val objCObjectBase = packageScope.getContributedClass("ObjCObjectBase")

    val allocObjCObject = packageScope.getContributedFunctions("allocObjCObject").single()

    val getObjCClass = packageScope.getContributedFunctions("getObjCClass").single()

    val objCObjectRawPtr = packageScope.getContributedFunctions("objcPtr").single()

    val getObjCReceiverOrSuper = packageScope.getContributedFunctions("getReceiverOrSuper").single()

    val getObjCMessenger = packageScope.getContributedFunctions("getMessenger").single()
    val getObjCMessengerStret = packageScope.getContributedFunctions("getMessengerStret").single()

    val interpretObjCPointerOrNull = packageScope.getContributedFunctions("interpretObjCPointerOrNull").single()
    val interpretObjCPointer = packageScope.getContributedFunctions("interpretObjCPointer").single()

    val objCObjectSuperInitCheck = packageScope.getContributedFunctions("superInitCheck").single()
    val objCObjectInitBy = packageScope.getContributedFunctions("initBy").single()

    val objCAction = packageScope.getContributedClass("ObjCAction")

    val objCOutlet = packageScope.getContributedClass("ObjCOutlet")

    val objCOverrideInit = objCObjectBase.unsubstitutedMemberScope.getContributedClass("OverrideInit")

    val objCMethodImp = packageScope.getContributedClass("ObjCMethodImp")

    val exportObjCClass = packageScope.getContributedClass("ExportObjCClass")

    val CreateNSStringFromKString = packageScope.getContributedFunctions("CreateNSStringFromKString").single()

}

private fun MemberScope.getContributedVariables(name: String) =
        this.getContributedVariables(Name.identifier(name), NoLookupLocation.FROM_BUILTINS)

private fun MemberScope.getContributedClass(name: String): ClassDescriptor =
        this.getContributedClassifier(Name.identifier(name), NoLookupLocation.FROM_BUILTINS) as ClassDescriptor

private fun MemberScope.getContributedFunctions(name: String) =
        this.getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BUILTINS)

private class InteropLibraryImpl(
        private val packageFqName: FqName,
        private val exportForwardDeclarations: List<FqName>
) : InteropLibrary {
    override fun createSyntheticPackages(
            module: ModuleDescriptor,
            konanPackageFragments: List<KonanPackageFragment>
    ): List<PackageFragmentDescriptor> {
        val interopPackageFragments = konanPackageFragments.filter { it.fqName == packageFqName }

        val result = mutableListOf<PackageFragmentDescriptor>()

        // Allow references to forwarding declarations to be resolved into classifiers declared in this library:
        listOf(InteropFqNames.cNamesStructs, InteropFqNames.objCNamesClasses, InteropFqNames.objCNamesProtocols).mapTo(result) { fqName ->
            ClassifierAliasingPackageFragmentDescriptor(interopPackageFragments, module, fqName)
        }
        // TODO: use separate namespaces for structs, enums, Objective-C protocols etc.

        result.add(ExportedForwardDeclarationsPackageFragmentDescriptor(
                module, packageFqName, exportForwardDeclarations
        ))

        return result
    }
}
