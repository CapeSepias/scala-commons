package com.avsystem.commons
package macros.rpc

import java.util.LinkedList

import com.avsystem.commons.macros.AbstractMacroCommons

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.reflect.macros.blackbox

class RPCMacros(ctx: blackbox.Context) extends AbstractMacroCommons(ctx) {

  import c.universe._

  val RpcPackage = q"$CommonsPackage.rpc"
  val RPCNameType: Type = getType(tq"$RpcPackage.RPCName")
  val RPCNameNameSym: Symbol = RPCNameType.member(TermName("name"))
  val AsRealCls = tq"$RpcPackage.AsReal"
  val AsRealObj = q"$RpcPackage.AsReal"
  val AsRawCls = tq"$RpcPackage.AsRaw"
  val AsRawObj = q"$RpcPackage.AsRaw"
  val AsRealRawCls = tq"$RpcPackage.AsRealRaw"
  val AsRealRawObj = q"$RpcPackage.AsRealRaw"
  val OptionLikeCls = tq"$RpcPackage.OptionLike"
  val CanBuildFromCls = tq"$CollectionPkg.generic.CanBuildFrom"

  val RpcArityAT: Type = getType(tq"$RpcPackage.RpcArity")
  val SingleArityAT: Type = getType(tq"$RpcPackage.single")
  val OptionalArityAT: Type = getType(tq"$RpcPackage.optional")
  val RepeatedArityAT: Type = getType(tq"$RpcPackage.repeated")
  val NamedRepeatedArityAT: Type = getType(tq"$RpcPackage.namedRepeated")
  val RpcEncodingAT: Type = getType(tq"$RpcPackage.RpcEncoding")
  val VerbatimAT: Type = getType(tq"$RpcPackage.verbatim")
  val RpcFilterAT: Type = getType(tq"$RpcPackage.RpcFilter")
  val AnnotatedWithAT: Type = getType(tq"$RpcPackage.annotatedWith[_]")
  val RawRPCCompanionTpe: Type = getType(tq"$RpcPackage.RawRPCCompanion[_]")

  val BIterableClass: ClassSymbol = rootMirror.staticClass("scala.collection.Iterable")
  val PartialFunctionClass: ClassSymbol = rootMirror.staticClass("scala.PartialFunction")

  sealed trait Res[+A] {
    def map[B](fun: A => B): Res[B] = this match {
      case Ok(value) => Ok(fun(value))
      case f: Failure => f
    }
    def flatMap[B](fun: A => Res[B]): Res[B] = this match {
      case Ok(value) => fun(value)
      case f: Failure => f
    }
    def toOption: Option[A] = this match {
      case Ok(value) => Some(value)
      case _ => None
    }
    def foreach(f: A => Any): Unit = this match {
      case Ok(value) => f(value)
      case _ =>
    }
  }
  case class Ok[+T](value: T) extends Res[T]
  case class Failure(message: String) extends Res[Nothing]

  sealed abstract class RpcArity(val verbatimByDefault: Boolean)
  object RpcArity {
    def fromAnnotation(annot: Tree, sym: RpcSymbol): RpcArity = {
      val at = annot.tpe
      if (at <:< SingleArityAT) RpcArity.Single
      else if (at <:< OptionalArityAT) RpcArity.Optional
      else if (at <:< RepeatedArityAT) RpcArity.Repeated
      else if (at <:< NamedRepeatedArityAT) RpcArity.NamedRepeated
      else sym.reportProblem(s"unrecognized RPC arity annotation: $annot", annot.pos)
    }

    case object Single extends RpcArity(true)
    case object Optional extends RpcArity(true)
    case object Repeated extends RpcArity(false)
    case object NamedRepeated extends RpcArity(false)
  }

  case class EncodedRealParam(realParam: RealParam, encoding: RpcEncoding) {
    def safeName: TermName = realParam.safeName
    def rawValueTree: Tree = q"${encoding.asRaw}.asRaw(${realParam.safeName})"
    def localValueDecl(body: Tree): Tree = realParam.localValueDecl(body)
  }

  sealed trait ParamMapping {
    def rawParam: RawParam
    def rawValueTree: Tree
    def realDecls(nameOfRealRpc: TermName): List[Tree]
  }
  object ParamMapping {
    case class Single(rawParam: RawParam, realParam: EncodedRealParam) extends ParamMapping {
      def rawValueTree: Tree =
        realParam.rawValueTree
      def realDecls(nameOfRealRpc: TermName): List[Tree] =
        List(realParam.localValueDecl(q"${realParam.encoding.asReal}.asReal(${rawParam.safeName})"))
    }
    case class Optional(rawParam: RawParam, wrapped: Option[EncodedRealParam]) extends ParamMapping {
      def rawValueTree: Tree =
        wrapped.fold[Tree](q"${rawParam.optionLike}.none")(erp => q"${rawParam.optionLike}.some(${erp.rawValueTree})")
      def realDecls(nameOfRealRpc: TermName): List[Tree] =
        wrapped.toList.map { erp =>
          val defaultValueTree = erp.realParam.defaultValueTree(nameOfRealRpc)
          erp.realParam.localValueDecl(
            q"${rawParam.optionLike}.fold(${rawParam.safeName}, $defaultValueTree)(${erp.encoding.asReal}.asReal)")
        }
    }
    case class Repeated(rawParam: RawParam, reals: List[EncodedRealParam]) extends ParamMapping {
      def rawValueTree: Tree = {
        val builderName = c.freshName(TermName("builder"))
        q"""
          val $builderName = ${rawParam.canBuildFrom}()
          ..${reals.map(erp => q"$builderName += ${erp.rawValueTree}")}
          $builderName.result()
         """
      }
      def realDecls(nameOfRealRpc: TermName): List[Tree] = {
        val itName = c.freshName(TermName("it"))
        val itDecl = q"val $itName = ${rawParam.safeName}.iterator"
        itDecl :: reals.map { erp =>
          val rp = erp.realParam
          val defaultValueTree = rp.defaultValueTree(nameOfRealRpc)
          if (rp.symbol.asTerm.isByNameParam) {
            rp.reportProblem(
              s"${rawParam.cannotMapClue}: by-name real parameters cannot be extracted from @repeated raw parameters")
          }
          erp.localValueDecl(
            q"if($itName.hasNext) ${erp.encoding.asReal}.asReal($itName.next()) else $defaultValueTree")
        }
      }
    }
    case class NamedRepeated(rawParam: RawParam, reals: List[EncodedRealParam]) extends ParamMapping {
      def rawValueTree: Tree = {
        val builderName = c.freshName(TermName("builder"))
        q"""
          val $builderName = ${rawParam.canBuildFrom}()
          ..${reals.map(erp => q"$builderName += ((${erp.realParam.rpcName}, ${erp.rawValueTree}))")}
          $builderName.result()
         """
      }
      def realDecls(nameOfRealRpc: TermName): List[Tree] =
        reals.map { erp =>
          val defaultValueTree = erp.realParam.defaultValueTree(nameOfRealRpc)
          erp.realParam.localValueDecl(
            q"""
            ${rawParam.safeName}.andThen(${erp.encoding.asReal}.asReal)
              .applyOrElse(${erp.realParam.rpcName}, (_: $StringCls) => $defaultValueTree)
            """)
        }
    }
  }

  sealed trait RpcFilter {
    def matches(sym: RpcSymbol): Boolean
  }
  object RpcFilter {
    def fromAnnotation(annot: Tree, sym: RpcSymbol): RpcFilter = {
      val tpe = annot.tpe
      if (tpe <:< AnnotatedWithAT) RpcFilter.AnnotatedWith(tpe.dealias.typeArgs.head)
      else sym.reportProblem(s"unrecognized RPC filter annotation: $annot", annot.pos)
    }

    case class AnnotatedWith(annotTpe: Type) extends RpcFilter {
      def matches(sym: RpcSymbol): Boolean = sym.annotations.exists(_.tpe <:< annotTpe)
    }
  }

  sealed trait RpcEncoding {
    def asRaw: Tree
    def asReal: Tree
  }
  object RpcEncoding {
    def forParam(rawParam: RawParam, realParam: RealParam): Res[RpcEncoding] = {
      if (rawParam.verbatim) {
        if (realParam.actualType =:= rawParam.encodedArgType)
          Ok(Verbatim(rawParam.encodedArgType))
        else rawParam.owner.matchFailure(
          s"${realParam.problemStr}: ${rawParam.cannotMapClue}: expected real parameter exactly of type " +
            s"${rawParam.encodedArgType}, got ${realParam.actualType}")
      } else
        Ok(RealRawEncoding(realParam.actualType, rawParam.encodedArgType,
          Some((s"${realParam.problemStr}: ${rawParam.cannotMapClue}: ", realParam.pos))))
    }

    case class Verbatim(tpe: Type) extends RpcEncoding {
      def asRaw = q"$AsRawObj.identity[$tpe]"
      def asReal = q"$AsRealObj.identity[$tpe]"
    }
    case class RealRawEncoding(realType: Type, rawType: Type, clueWithPos: Option[(String, Position)]) extends RpcEncoding {
      private def infer(convClass: Tree): TermName = {
        val convTpe = getType(tq"$convClass[$realType,$rawType]")
        clueWithPos match {
          case Some((clue, pos)) => inferCachedImplicit(convTpe, clue, pos)
          case None => tryInferCachedImplicit(convTpe).getOrElse(termNames.EMPTY)
        }
      }
      lazy val asRawName: TermName = infer(AsRawCls)
      lazy val asRealName: TermName = infer(AsRealCls)
      def asRaw = q"$asRawName"
      def asReal = q"$asRealName"
    }
  }

  abstract class RpcSymbol {
    val symbol: Symbol
    def pos: Position = symbol.pos
    def problemStr: String

    def reportProblem(msg: String, detailPos: Position = NoPosition): Nothing =
      abortAt(s"$problemStr: $msg", if (detailPos != NoPosition) detailPos else pos)

    val name: TermName = symbol.name.toTermName
    val safeName: TermName = c.freshName(symbol.name.toTermName)
    val nameStr: String = name.decodedName.toString
    val encodedNameStr: String = name.encodedName.toString

    lazy val annotations: List[Tree] = allAnnotations(symbol)

    lazy val filters: List[RpcFilter] = annotations.collect {
      case filterAnnot if filterAnnot.tpe <:< RpcFilterAT => RpcFilter.fromAnnotation(filterAnnot, this)
    }

    lazy val rpcName: String =
      annotations.find(_.tpe <:< RPCNameType)
        .map { annot =>
          findAnnotationArg(annot, RPCNameNameSym) match {
            case StringLiteral(n) => n
            case p => reportProblem("The name argument of @RPCName must be a string literal", p.pos)
          }
        }.getOrElse(nameStr)

    override def equals(other: Any): Boolean = other match {
      case rpcSym: RpcSymbol => symbol == rpcSym.symbol
      case _ => false
    }
    override def hashCode: Int = symbol.hashCode
    override def toString: String = symbol.toString
  }

  abstract class RpcMethod extends RpcSymbol {
    val owner: Type
    def problemStr = s"problem with method $nameStr of type $owner"

    if (!symbol.isMethod) {
      abortAt(s"problem with member $nameStr of type $owner: it must be a method (def)", pos)
    }

    val sig: Type = symbol.typeSignatureIn(owner)
    if (sig.typeParams.nonEmpty) {
      // can we relax this?
      reportProblem("RPC methods must not be generic")
    }

    val paramLists: List[List[RpcParam]]
    val resultType: Type = sig.finalResultType

    def argLists: List[List[Tree]] = paramLists.map(_.map(_.argToPass))
    def paramDecls: List[List[Tree]] = paramLists.map(_.map(_.paramDecl))
  }

  abstract class RpcParam extends RpcSymbol {
    val owner: RpcMethod
    def problemStr = s"problem with parameter $nameStr of method ${owner.nameStr}"
    val actualType: Type = actualParamType(symbol)

    def localValueDecl(body: Tree): Tree =
      if (symbol.asTerm.isByNameParam)
        q"def $safeName = $body"
      else
        q"val $safeName = $body"

    def paramDecl: Tree = {
      val implicitFlag = if (symbol.isImplicit) Flag.IMPLICIT else NoFlags
      ValDef(Modifiers(Flag.PARAM | implicitFlag), safeName, TypeTree(symbol.typeSignature), EmptyTree)
    }

    def argToPass: Tree =
      if (isRepeated(symbol)) q"$safeName: _*" else q"$safeName"
  }

  case class RpcNameParam(owner: RawMethod, symbol: Symbol) extends RpcParam

  case class RawParam(owner: RawMethod, symbol: Symbol) extends RpcParam {
    def cannotMapClue = s"cannot map it to raw parameter $nameStr of ${owner.nameStr}"

    val arity: RpcArity =
      annotations.find(_.tpe <:< RpcArityAT).map(RpcArity.fromAnnotation(_, this)).getOrElse(RpcArity.Single)

    val verbatim: Boolean =
      annotations.find(_.tpe <:< RpcEncodingAT).map(_.tpe <:< VerbatimAT).getOrElse(arity.verbatimByDefault)

    private def infer(tpt: Tree): TermName =
      inferCachedImplicit(getType(tpt), s"$problemStr: ", pos)

    lazy val optionLike: TermName = infer(tq"$OptionLikeCls[$actualType]")

    lazy val encodedArgType: Type = arity match {
      case RpcArity.Single => actualType
      case RpcArity.Optional =>
        val optionLikeType = typeOfCachedImplicit(optionLike)
        val valueMember = optionLikeType.member(TypeName("Value"))
        if (valueMember.isAbstract) {
          reportProblem("could not determine actual value of optional parameter type")
        }
        valueMember.typeSignatureIn(optionLikeType)
      case RpcArity.Repeated =>
        if (actualType <:< typeOf[Iterable[Any]])
          actualType.baseType(BIterableClass).typeArgs.head
        else
          reportProblem("@repeated raw parameter must be an Iterable")
      case RpcArity.NamedRepeated =>
        if (actualType <:< typeOf[PartialFunction[String, Any]])
          actualType.baseType(PartialFunctionClass).typeArgs(1)
        else
          reportProblem(s"@namedRepeated raw parameter must be a PartialFunction of String")
    }

    lazy val canBuildFrom: TermName = arity match {
      case RpcArity.Repeated =>
        infer(tq"$CanBuildFromCls[$NothingCls,$encodedArgType,$actualType]")
      case RpcArity.NamedRepeated =>
        infer(tq"$CanBuildFromCls[$NothingCls,($StringCls,$encodedArgType),$actualType]")
      case _ => termNames.EMPTY
    }

    def filtersMatch(realMethod: RealParam): Boolean =
      filters.forall(_.matches(realMethod))

    def extractMapping(realParams: LinkedList[RealParam]): Res[ParamMapping] = {
      val it = realParams.listIterator()

      def extractSingle(): Res[EncodedRealParam] =
        if (it.hasNext) {
          val realParam = it.next()
          if (filtersMatch(realParam)) {
            it.remove()
            RpcEncoding.forParam(this, realParam).map(e => EncodedRealParam(realParam, e))
          } else extractSingle()
        } else {
          owner.matchFailure(s"raw parameter $nameStr was not matched by real parameter")
        }

      def extractOptional(): Option[EncodedRealParam] =
        if (it.hasNext) {
          val realParam = it.next()
          if (filtersMatch(realParam)) {
            RpcEncoding.forParam(this, realParam).toOption.map { encoding =>
              it.remove()
              EncodedRealParam(realParam, encoding)
            }
          } else extractOptional()
        } else None

      def extractRepeated() = {
        def loop(): Res[List[EncodedRealParam]] =
          if (it.hasNext) {
            val realParam = it.next()
            if (filtersMatch(realParam)) {
              it.remove()
              for {
                encoding <- RpcEncoding.forParam(this, realParam)
                rest <- loop()
              } yield EncodedRealParam(realParam, encoding) :: rest
            } else loop()
          } else Ok(Nil)
        val result = loop()
        for {
          rps <- result
          (rpcName, first :: rest) <- rps.groupBy(_.realParam.rpcName) if rest.nonEmpty
        } {
          first.realParam.owner.reportProblem(
            s"multiple parameters matched to raw parameter $nameStr have the same @RPCName: $rpcName")
        }
        result
      }


      arity match {
        case RpcArity.Single => extractSingle().map(ParamMapping.Single(this, _))
        case RpcArity.Optional => Ok(ParamMapping.Optional(this, extractOptional()))
        case RpcArity.Repeated => extractRepeated().map(ParamMapping.Repeated(this, _))
        case RpcArity.NamedRepeated => extractRepeated().map(ParamMapping.NamedRepeated(this, _))
      }
    }
  }

  case class RealParam(owner: RealMethod, symbol: Symbol, index: Int, indexInList: Int) extends RpcParam {
    def defaultValueTree(nameOfRealRpc: TermName): Tree =
      if (symbol.asTerm.isParamWithDefault) {
        val prevListParams = owner.realParams.take(index - indexInList).map(rp => q"${rp.safeName}")
        val prevListParamss = List(prevListParams).filter(_.nonEmpty)
        q"$nameOfRealRpc.${TermName(s"${owner.encodedNameStr}$$default$$${index + 1}")}(...$prevListParamss)"
      }
      else
        q"$RpcPackage.RpcUtils.missingArg(${owner.mapping.rawMethod.rpcNameParam.safeName}, $rpcName)"
  }

  case class RawMethod(owner: Type, symbol: Symbol) extends RpcMethod {
    // raw method result is encoded by default, must be explicitly annotated as @verbatim to disable encoding
    val verbatimResult: Boolean =
      annotations.find(_.tpe <:< RpcEncodingAT).exists(_.tpe <:< VerbatimAT)

    def filtersMatch(realMethod: RealMethod): Boolean =
      filters.forall(_.matches(realMethod))

    def matchFailure(msg: String): Failure =
      Failure(s"raw method $nameStr did not match because: $msg")

    val (rpcNameParam, rawParams, paramLists) =
      sig.paramLists match {
        case (nameParam :: tailFirst) :: rest if nameParam.typeSignature =:= typeOf[String] =>
          val np = RpcNameParam(this, nameParam)
          val tailFirstRaw = tailFirst.map(RawParam(this, _))
          val restRaw = rest.map(_.map(RawParam(this, _)))
          val rp: List[RawParam] = tailFirstRaw ::: restRaw.flatten
          val pl: List[List[RpcParam]] = (np :: tailFirstRaw) :: restRaw
          (np, rp, pl)
        case _ =>
          reportProblem("raw RPC method must take at least RPC name (a String) as its first parameter")
      }

    def rawImpl(caseDefs: List[CaseDef]): Tree =
      q"""
        def $name(...$paramDecls): $resultType =
          ${rpcNameParam.safeName} match {
            case ..$caseDefs
            case _ => $RpcPackage.RpcUtils.unknownRpc(${rpcNameParam.safeName}, $nameStr)
          }
       """
  }

  case class RealMethod(owner: Type, symbol: Symbol, rawMethods: List[RawMethod],
    forAsRaw: Boolean, forAsReal: Boolean) extends RpcMethod {

    val paramLists: List[List[RealParam]] = {
      var idx = 0
      sig.paramLists.map { ss =>
        var listIdx = 0
        ss.map { s =>
          val res = RealParam(this, s, idx, listIdx)
          idx += 1
          listIdx += 1
          res
        }
      }
    }

    val realParams: List[RealParam] = paramLists.flatten

    val mappingRes: Res[MethodMapping] = {
      val methodMappings = rawMethods.filter(_.filtersMatch(this)).map { rawMethod =>
        def resultEncoding: Res[RpcEncoding] =
          if (rawMethod.verbatimResult) {
            if (rawMethod.resultType =:= resultType)
              Ok(RpcEncoding.Verbatim(rawMethod.resultType))
            else rawMethod.matchFailure(
              s"real result type $resultType does not match raw result type ${rawMethod.resultType}")
          } else {
            val e = RpcEncoding.RealRawEncoding(resultType, rawMethod.resultType, None)
            if ((!forAsRaw || e.asRawName != termNames.EMPTY) && (!forAsReal || e.asRealName != termNames.EMPTY))
              Ok(e)
            else rawMethod.matchFailure(
              s"no encoding/decoding found between real result type $resultType and raw result type ${rawMethod.resultType}")
          }

        def collectParamMappings(rawParams: List[RawParam], realParams: List[RealParam]): Res[List[ParamMapping]] = {
          val realBuf = new LinkedList[RealParam]
          realBuf.addAll(realParams.asJava)

          val initialAcc: Res[List[ParamMapping]] = Ok(Nil)
          rawParams.foldLeft(initialAcc) { (accOpt, rawParam) =>
            for {
              acc <- accOpt
              mapping <- rawParam.extractMapping(realBuf)
            } yield mapping :: acc
          }.flatMap { result =>
            if (realBuf.isEmpty) Ok(result.reverse)
            else {
              val unmatched = realBuf.iterator.asScala.map(_.nameStr).mkString(",")
              rawMethod.matchFailure(s"no raw parameter(s) were found that would match real parameter(s) $unmatched")
            }
          }
        }

        for {
          resultConv <- resultEncoding
          paramMappings <- collectParamMappings(rawMethod.rawParams, realParams)
        } yield MethodMapping(this, rawMethod, paramMappings, resultConv)
      }

      methodMappings.collect { case Ok(m) => m } match {
        case List(m) => Ok(m)
        case Nil =>
          val unmatchedReport = methodMappings.iterator.collect({ case Failure(error) => s" * $error" }).mkString("\n")
          Failure(s"it has no matching raw methods:\n$unmatchedReport")
        case multiple =>
          Failure(s"it has multiple matching raw methods: ${multiple.map(_.rawMethod.nameStr).mkString(",")}")
      }
    }

    def mapping: MethodMapping = mappingRes match {
      case Ok(m) => m
      case Failure(error) => reportProblem(error)
    }
  }

  case class MethodMapping(realMethod: RealMethod, rawMethod: RawMethod,
    paramMappings: List[ParamMapping], resultEncoding: RpcEncoding) {

    def realImpl(rawName: TermName): Tree = {
      val rawParamDefns = paramMappings.map(pm => pm.rawParam.localValueDecl(pm.rawValueTree))

      q"""
        def ${realMethod.name}(...${realMethod.paramDecls}): ${realMethod.resultType} = {
          val ${rawMethod.rpcNameParam.safeName} = ${realMethod.rpcName}
          ..$rawParamDefns
          ${resultEncoding.asReal}.asReal($rawName.${rawMethod.name}(...${rawMethod.argLists}))
        }
       """
    }

    def rawCaseImpl(nameOfRealRpc: TermName): CaseDef = {
      cq"""
        ${realMethod.rpcName} =>
          ..${paramMappings.flatMap(_.realDecls(nameOfRealRpc))}
          ${resultEncoding.asRaw}.asRaw($nameOfRealRpc.${realMethod.name}(...${realMethod.argLists}))
        """
    }
  }

  def checkImplementable(tpe: Type): Unit = {
    val sym = tpe.dealias.typeSymbol
    if (!sym.isAbstract || !sym.isClass) {
      abortAt(s"$tpe must be an abstract class or trait", sym.pos)
    }
  }

  def extractRawMethods(rawTpe: Type): List[RawMethod] =
    rawTpe.members.iterator.filter(m => m.isTerm && m.isAbstract).map(RawMethod(rawTpe, _)).toList

  def extractRealMethods(realTpe: Type, rawMethods: List[RawMethod],
    forAsRaw: Boolean, forAsReal: Boolean): List[RealMethod] = {

    val result = realTpe.members.iterator.filter(m => m.isTerm && m.isAbstract)
      .map(RealMethod(realTpe, _, rawMethods, forAsRaw, forAsReal)).toList
    val failedMethods = result.flatMap { rm =>
      rm.mappingRes match {
        case Failure(msg) => Some((rm, msg))
        case _ => None
      }
    }
    if (failedMethods.nonEmpty) {
      val failedMethodsRepr = failedMethods.map { case (rm, msg) =>
        if (rm.pos != NoPosition) {
          c.error(rm.pos, s"Macro expansion at ${posInfo(c.enclosingPosition)} failed: ${rm.problemStr}: $msg")
        } else {
          error(s"${rm.problemStr}: $msg")
        }
        rm.nameStr
      }.mkString(",")
      abort(s"Following real methods could not be mapped to raw methods: $failedMethodsRepr")
    }
    result
  }

  private def asRealImpl(realTpe: Type, rawTpe: Type, raws: List[RawMethod], reals: List[RealMethod]): Tree = {
    val rawName = c.freshName(TermName("raw"))
    val realMethodImpls = reals.map(_.mapping.realImpl(rawName))

    q"""
      def asReal($rawName: $rawTpe): $realTpe = new $realTpe {
        ..$realMethodImpls; ()
      }
      """
  }

  private def asRawImpl(realTpe: Type, rawTpe: Type, raws: List[RawMethod], reals: List[RealMethod]): Tree = {
    val realName = c.freshName(TermName("real"))

    val caseDefs = raws.iterator.map(rm => (rm, new mutable.LinkedHashMap[String, CaseDef])).toMap
    reals.foreach { realMethod =>
      val mapping = realMethod.mapping
      val prevCaseDef = caseDefs(mapping.rawMethod).put(realMethod.rpcName, mapping.rawCaseImpl(realName))
      ensure(prevCaseDef.isEmpty,
        s"Multiple RPCs named ${realMethod.rpcName} map to raw method ${mapping.rawMethod.name}. " +
          "If you want to overload RPCs, disambiguate them with @RPCName annotation")
    }

    val rawMethodImpls = raws.map(m => m.rawImpl(caseDefs(m).values.toList))

    q"""
      def asRaw($realName: $realTpe): $rawTpe = new $rawTpe {
        ..$rawMethodImpls; ()
      }
      """
  }

  private def registerCompanionImplicits(rawTpe: Type): Unit =
    companionOf(rawTpe).filter { companion =>
      val typed = c.typecheck(q"$companion.implicits", silent = true)
      typed != EmptyTree && !(typed.tpe.widen =:= typeOf[Any])
    }.foreach { companion =>
      registerImplicitImport(q"import $companion.implicits._")
    }

  def rpcAsReal[T: WeakTypeTag, R: WeakTypeTag]: Tree = {
    val realTpe = weakTypeOf[T]
    checkImplementable(realTpe)
    val rawTpe = weakTypeOf[R]
    checkImplementable(rawTpe)

    val selfName = c.freshName(TermName("self"))
    registerImplicit(getType(tq"$AsRealCls[$realTpe,$rawTpe]"), selfName)
    registerCompanionImplicits(rawTpe)

    val raws = extractRawMethods(rawTpe)
    val reals = extractRealMethods(realTpe, raws, forAsRaw = false, forAsReal = true)
    // must be evaluated before `cachedImplicitDeclarations`, don't inline it into the quasiquote
    val asRealDef = asRealImpl(realTpe, rawTpe, raws, reals)

    q"""
      new $AsRealCls[$realTpe,$rawTpe] { $selfName: ${TypeTree()} =>
        ..$cachedImplicitDeclarations
        $asRealDef
      }
    """
  }

  def rpcAsRaw[T: WeakTypeTag, R: WeakTypeTag]: Tree = {
    val realTpe = weakTypeOf[T]
    checkImplementable(realTpe)
    val rawTpe = weakTypeOf[R]
    checkImplementable(rawTpe)

    val selfName = c.freshName(TermName("self"))
    registerImplicit(getType(tq"$AsRawCls[$realTpe,$rawTpe]"), selfName)
    registerCompanionImplicits(rawTpe)

    val raws = extractRawMethods(rawTpe)
    val reals = extractRealMethods(realTpe, raws, forAsRaw = true, forAsReal = false)
    // must be evaluated before `cachedImplicitDeclarations`, don't inline it into the quasiquote
    val asRawDef = asRawImpl(realTpe, rawTpe, raws, reals)

    q"""
      new $AsRawCls[$realTpe,$rawTpe] { $selfName: ${TypeTree()} =>
        ..$cachedImplicitDeclarations
        $asRawDef
      }
     """
  }

  def rpcAsRealRaw[T: WeakTypeTag, R: WeakTypeTag]: Tree = {
    val realTpe = weakTypeOf[T]
    checkImplementable(realTpe)
    val rawTpe = weakTypeOf[R]
    checkImplementable(rawTpe)

    val selfName = c.freshName(TermName("self"))
    registerImplicit(getType(tq"$AsRawCls[$realTpe,$rawTpe]"), selfName)
    registerImplicit(getType(tq"$AsRealCls[$realTpe,$rawTpe]"), selfName)
    registerCompanionImplicits(rawTpe)

    val raws = extractRawMethods(rawTpe)
    val reals = extractRealMethods(realTpe, raws, forAsRaw = true, forAsReal = true)

    // these two must be evaluated before `cachedImplicitDeclarations`, don't inline them into the quasiquote
    val asRealDef = asRealImpl(realTpe, rawTpe, raws, reals)
    val asRawDef = asRawImpl(realTpe, rawTpe, raws, reals)

    q"""
      new $AsRealRawCls[$realTpe,$rawTpe] { $selfName: ${TypeTree()} =>
        ..$cachedImplicitDeclarations
        $asRealDef
        $asRawDef
      }
     """
  }
}
