package com.avsystem.commons
package macros

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.reflect.macros.{TypecheckException, blackbox}
import scala.util.control.NoStackTrace

trait MacroCommons { bundle =>
  val c: blackbox.Context

  import c.universe._

  final val ScalaPkg = q"_root_.scala"
  final val StringCls = tq"_root_.java.lang.String"
  final val ClassCls = tq"_root_.java.lang.Class"
  final val NothingCls = tq"_root_.scala.Nothing"
  final val CommonsPackage = q"_root_.com.avsystem.commons"
  final val PredefObj = q"$ScalaPkg.Predef"
  final val UnitCls = tq"$ScalaPkg.Unit"
  final val OptionCls = tq"$ScalaPkg.Option"
  final val OptionObj = q"$ScalaPkg.Option"
  final val SomeObj = q"$ScalaPkg.Some"
  final val NoneObj = q"$ScalaPkg.None"
  final val CollectionPkg = q"$ScalaPkg.collection"
  final val ListObj = q"$CollectionPkg.immutable.List"
  final val ListCls = tq"$CollectionPkg.immutable.List"
  final val SetObj = q"$CollectionPkg.immutable.Set"
  final val SetCls = tq"$CollectionPkg.immutable.Set"
  final val NilObj = q"$CollectionPkg.immutable.Nil"
  final val MapObj = q"$CollectionPkg.immutable.Map"
  final val MapCls = tq"$CollectionPkg.immutable.Map"
  final val MapSym = typeOf[scala.collection.immutable.Map[_, _]].typeSymbol
  final val MaterializedCls = tq"$CommonsPackage.derivation.Materialized"
  final val FutureSym = typeOf[scala.concurrent.Future[_]].typeSymbol
  final val OptionClass = definitions.OptionClass
  final val ImplicitsObj = q"$CommonsPackage.misc.Implicits"
  final val AnnotationAggregateType = getType(tq"$CommonsPackage.annotation.AnnotationAggregate")

  final lazy val isScalaJs =
    definitions.ScalaPackageClass.toType.member(TermName("scalajs")) != NoSymbol

  final lazy val ownerChain = {
    val sym = c.typecheck(q"val ${c.freshName(TermName(""))} = null").symbol
    Iterator.iterate(sym)(_.owner).takeWhile(_ != NoSymbol).drop(1).toList
  }

  final lazy val enclosingClasses = {
    val enclosingSym = c.typecheck(q"this", silent = true) match {
      case EmptyTree => Iterator.iterate(c.internal.enclosingOwner)(_.owner).find(_.isModuleClass).get
      case tree => tree.tpe.typeSymbol
    }
    Iterator.iterate(enclosingSym)(_.owner).takeWhile(_ != NoSymbol).toList
  }

  // simplified representation of trees of implicits, used to remove duplicated implicits,
  // i.e. implicits that were found for different types but turned out to be identical
  private sealed trait ImplicitTrace
  private object ImplicitTrace {
    def apply(tree: Tree): Option[ImplicitTrace] =
      try Some(extract(tree)) catch {
        case TranslationFailed => None
      }

    private val TranslationFailed = new RuntimeException with NoStackTrace
    private def extract(tree: Tree): ImplicitTrace = tree match {
      case Literal(Constant(tpe: Type)) => Lit(TypeKey(tpe))
      case Literal(Constant(value)) => Lit(value)
      case Ident(_) if tree.symbol.isStatic => Static(tree.symbol)
      case Ident(name) => Id(name)
      case This(name) => Ths(name)
      case Select(pre, name) => Sel(extract(pre), name)
      case Apply(pre, args) => App(extract(pre), args.map(extract))
      case TypeApply(fun, _) => extract(fun)
      case Typed(expr, _) => extract(expr)
      case Annotated(_, arg) => extract(arg)
      case _ => throw TranslationFailed // this can probably only happen when implicit is a macro
    }

    case class Lit(value: Any) extends ImplicitTrace
    case class Id(name: Name) extends ImplicitTrace
    case class Ths(name: Name) extends ImplicitTrace
    case class Static(sym: Symbol) extends ImplicitTrace
    case class Sel(prefix: ImplicitTrace, name: Name) extends ImplicitTrace
    case class App(prefix: ImplicitTrace, args: List[ImplicitTrace]) extends ImplicitTrace
  }

  private val implicitSearchCache = new mutable.HashMap[TypeKey, Option[TermName]]
  private val implicitsByTrace = new mutable.HashMap[ImplicitTrace, TermName]
  private val inferredImplicitTypes = new mutable.HashMap[TermName, Type]
  private val implicitImports = new ListBuffer[Tree]
  private val implicitsToDeclare = new ListBuffer[Tree]

  def tryInferCachedImplicit(tpe: Type): Option[TermName] = {
    def compute: Option[TermName] =
      Option(c.typecheck(q"..$implicitImports; implicitly[$tpe]", silent = true))
        .filter(_ != EmptyTree)
        .map { case WrappedImplicitSearchResult(t) =>
          def newCachedImplicit(): TermName = {
            val name = c.freshName(TermName("cachedImplicit"))
            inferredImplicitTypes(name) = t.tpe
            implicitsToDeclare += q"private lazy val $name = $t"
            name
          }
          ImplicitTrace(t).fold(newCachedImplicit()) { tr =>
            implicitsByTrace.get(tr).filter(n => inferredImplicitTypes(n) =:= t.tpe).getOrElse {
              val name = newCachedImplicit()
              implicitsByTrace(tr) = name
              name
            }
          }
        }
    implicitSearchCache.getOrElseUpdate(TypeKey(tpe), compute)
  }

  def inferCachedImplicit(tpe: Type, errorClue: String, errorPos: Position): TermName =
    tryInferCachedImplicit(tpe).getOrElse {
      val name = c.freshName(TermName(""))
      implicitSearchCache(TypeKey(tpe)) = Some(name)
      implicitsToDeclare +=
        q"private implicit val $name = $ImplicitsObj.infer[$tpe](${internal.setPos(StringLiteral(errorClue), errorPos)})"
      inferredImplicitTypes(name) = tpe
      inferCachedImplicit(tpe, errorClue, errorPos)
    }

  def typeOfCachedImplicit(name: TermName): Type =
    inferredImplicitTypes.getOrElse(name, NoType)

  def registerImplicit(tpe: Type, name: TermName): Unit = {
    implicitSearchCache(TypeKey(tpe)) = Some(name)
    inferredImplicitTypes(name) = tpe
  }

  def registerImplicitImport(importTree: Tree): Unit =
    implicitImports += importTree

  def cachedImplicitDeclarations: List[Tree] =
    implicitsToDeclare.result()

  implicit class treeOps[T <: Tree](t: T) {
    def debug: T = {
      c.echo(c.enclosingPosition, show(t))
      t
    }

    def asStatList: List[Tree] = t match {
      case Block(stats, expr) => stats :+ expr
      case _ => List(t)
    }
  }

  def paramIndex(param: Symbol): Int =
    param.owner.typeSignature.paramLists.flatten.indexOf(param) + 1

  def superSymbols(s: Symbol): List[Symbol] = s match {
    case cs: ClassSymbol => cs.baseClasses
    case ms: MethodSymbol => ms :: ms.overrides
    case ps: TermSymbol if ps.isParameter && ps.owner.isMethod =>
      val oms = ps.owner.asMethod
      val paramListIdx = oms.paramLists.indexWhere(_.exists(_.name == ps.name))
      val paramIdx = oms.paramLists(paramListIdx).indexWhere(_.name == ps.name)
      superSymbols(oms).map(_.asMethod.paramLists(paramListIdx)(paramIdx))
    case _ => List(s)
  }

  def primaryConstructorOf(tpe: Type) =
    alternatives(tpe.member(termNames.CONSTRUCTOR)).find(_.asMethod.isPrimaryConstructor)
      .getOrElse(abort(s"No primary constructor found for $tpe"))

  class AnnotationArgInliner(baseAnnot: Tree) extends Transformer {
    private val argsByName: Map[Name, Tree] = {
      val Apply(_, args) = baseAnnot
      val paramNames = primaryConstructorOf(baseAnnot.tpe).typeSignature.paramLists.head.map(_.name)
      (paramNames zip args).toMap
    }

    override def transform(tree: Tree): Tree = tree match {
      case Select(th@This(_), name) if th.symbol == baseAnnot.tpe.typeSymbol
        && tree.symbol.asTerm.isParamAccessor =>
        argsByName.get(name).map(_.duplicate).getOrElse(tree)
      case _ => super.transform(tree)
    }
  }

  def aggregatedAnnotations(annot: Tree): List[Tree] = {
    val annotTpe = annot.tpe
    if (annotTpe <:< AnnotationAggregateType) {
      val argsInliner = new AnnotationArgInliner(annot)
      annotTpe.member(TypeName("Implied")).annotations
        .map(a => argsInliner.transform(a.tree))
        .flatMap(t => t :: aggregatedAnnotations(t))
    } else Nil
  }

  def allAnnotations(s: Symbol): List[Tree] =
    superSymbols(s).map(_.annotations.map(_.tree)).flatMap(as => as ::: as.flatMap(aggregatedAnnotations))

  def findAnnotationArg(constrCall: Tree, valSym: Symbol): Tree = constrCall match {
    case Apply(Select(New(tpt), termNames.CONSTRUCTOR), args) =>
      val clsTpe = tpt.tpe
      val params = primaryConstructorOf(clsTpe).typeSignature.paramLists.head
      ensure(params.size == args.size, s"Not a primary constructor call tree: $constrCall")
      val subSym = clsTpe.member(valSym.name)
      ensure(subSym.isTerm && subSym.asTerm.isParamAccessor && (subSym :: subSym.overrides).contains(valSym),
        s"Annotation $clsTpe must override $valSym with a constructor parameter")
      (params zip args)
        .collectFirst { case (param, arg) if param.name == subSym.name => arg }
        .getOrElse(abort(s"Could not find argument corresponding to constructor parameter ${subSym.name}"))
    case _ => abort(s"Not a primary constructor call tree: $constrCall")
  }

  def abort(msg: String) =
    c.abort(c.enclosingPosition, msg)

  def echo(msg: String) =
    c.echo(c.enclosingPosition, msg)

  def error(msg: String) =
    c.error(c.enclosingPosition, msg)

  def warning(msg: String) =
    c.warning(c.enclosingPosition, msg)

  def ensure(condition: Boolean, msg: String): Unit =
    if (!condition) {
      abort(msg)
    }

  def posInfo(pos: Position): String =
    s"${pos.source.file.name}:${pos.line}:${pos.column}"

  def abortAt(message: String, pos: Position): Nothing =
    if (pos != NoPosition) {
      c.error(pos, s"Macro expansion at ${posInfo(c.enclosingPosition)} failed: $message")
      abort(s"Macro expansion failed because of error at ${posInfo(pos)}")
    } else {
      abort(message)
    }

  /**
    * Wrapper over Type that implements equals/hashCode consistent with type equivalence (=:=)
    */
  case class TypeKey(tpe: Type) {
    override def equals(obj: Any) = obj match {
      case TypeKey(otherTpe) => tpe =:= otherTpe
      case _ => false
    }
    override lazy val hashCode: Int = {
      val dealiased = tpe.map(_.dealias)
      val innerSymbols = new mutable.HashSet[Symbol]

      def collectInnerSymbols(tpe: Type): Unit = tpe match {
        case PolyType(ts, _) =>
          innerSymbols ++= ts
        case ExistentialType(ts, _) =>
          innerSymbols ++= ts
        case rt@RefinedType(_, scope) =>
          innerSymbols += rt.typeSymbol
          innerSymbols ++= scope
          innerSymbols ++= scope.flatMap(_.typeSignature.typeParams)
          innerSymbols ++= scope.flatMap(_.typeSignature.paramLists.flatten)
        case _ =>
      }

      dealiased.foreach(collectInnerSymbols)
      val hashCodeSymbols = new mutable.ListBuffer[Symbol]
      dealiased.foreach {
        case ThisType(sym) if !innerSymbols(sym) =>
          hashCodeSymbols += sym
        case SingleType(_, sym) if !innerSymbols(sym) =>
          hashCodeSymbols += sym
        case TypeRef(_, sym, _) if !innerSymbols(sym) =>
          hashCodeSymbols += sym
        case _ =>
      }
      hashCodeSymbols.result().hashCode
    }
  }

  def getType(typeTree: Tree): Type =
    c.typecheck(typeTree, c.TYPEmode).tpe

  def pathTo(sym: Symbol): Tree =
    if (enclosingClasses.contains(sym)) This(sym)
    else if (sym.isModuleClass) Select(pathTo(sym.owner), if (sym.isModuleClass) sym.name.toTermName else sym.name)
    else This(sym)

  def isTypeTree(tree: Tree): Boolean = tree match {
    case Ident(TypeName(_)) | Select(_, TypeName(_)) => true
    case _ => tree.isType
  }

  def select(pre: Tree, name: Name): Tree = pre match {
    case SingletonTypeTree(ref) if name.isTermName => select(ref, name)
    case t if isTypeTree(t) && name.isTypeName => SelectFromTypeTree(t, name.toTypeName)
    case t if name.isTermName => SingletonTypeTree(Select(t, name))
    case t => Select(t, name)
  }

  object StringLiteral {
    def unapply(tree: Tree): Option[String] = tree match {
      case Literal(Constant(str: String)) => Some(str)
      case _ => None
    }
    def apply(str: String): Tree = Literal(Constant(str))
  }

  object BooleanLiteral {
    def unapply(tree: Tree): Option[Boolean] = tree match {
      case Literal(Constant(boolean: Boolean)) => Some(boolean)
      case _ => None
    }
  }

  object Lit {
    def unapply(tree: Tree): Option[Any] = tree match {
      case Literal(Constant(value)) => Some(value)
      case _ => None
    }
  }

  case class LitOrDefault[T: ClassTag](default: T) {
    def unapply(tree: Tree): Option[T] = tree match {
      case Literal(Constant(value: T)) => Some(value)
      case Select(_, TermName(n)) if n.startsWith("$lessinit$greater$default$") => Some(default)
      case _ => None
    }
  }

  object ExistentialSingleton {
    def unapply(sym: Symbol): Option[(TypeSymbol, TermName, Type)] =
      if (sym.isType && sym.name.decodedName.toString.endsWith(".type")) {
        val ts = sym.asType
        if (ts.isExistential) ts.typeSignature match {
          case TypeBounds(lo, RefinedType(bases, scope))
            if lo =:= typeOf[Nothing] && bases.size >= 2 && bases.exists(_ =:= typeOf[Singleton]) =>

            val strName = sym.name.decodedName.toString.stripSuffix(".type")
            val newBases = bases.filterNot(_ =:= typeOf[Singleton])
            val newSig = newBases match {
              case List(singleBase) if scope.isEmpty => singleBase
              case _ => internal.refinedType(newBases, scope)
            }
            Some((ts, TermName(strName).encodedName.toTermName, newSig))
          case _ => None
        } else None
      } else None
  }

  object AnonPartialFunction {
    def unapply(tree: Tree): Option[List[CaseDef]] = tree match {
      case Typed(Block(List(ClassDef(_, _, _, Template(_, _, List(_, DefDef(_, _, _, _, _, Match(_, cases)), _)))), _), _)
        if tree.tpe <:< typeOf[PartialFunction[Nothing, Any]] =>
        Some(cases)
      case _ => None
    }
  }

  object MaybeTypeApply {
    def unapply(tree: Tree): Some[(Tree, List[Tree])] = tree match {
      case TypeApply(fun, args) => Some((fun, args))
      case _ => Some((tree, Nil))
    }
  }

  object MultiApply {
    def unapply(tree: Tree): Some[(Tree, List[List[Tree]])] = {
      def collect(tree: Tree, argLists: List[List[Tree]]): (Tree, List[List[Tree]]) = tree match {
        case Apply(fun, args) => collect(fun, args :: argLists)
        case fun => (fun, argLists)
      }
      Some(collect(tree, Nil))
    }
  }

  object WrappedImplicitSearchResult {
    def unapply(tree: Tree): Option[Tree] = tree match {
      case Block(_, expr) => unapply(expr)
      case Apply(_, List(arg)) => Some(arg)
    }
  }

  /**
    * Returns a `Tree` that should typecheck to the type passed as argument (without using `TypeTree`).
    */
  def treeForType(tpe: Type): Tree = tpe match {
    case TypeRef(NoPrefix, ExistentialSingleton(_, name, _), Nil) =>
      Ident(name)
    case TypeRef(NoPrefix, sym, Nil) =>
      Ident(sym.name)
    case TypeRef(pre, sym, Nil) =>
      select(treeForType(pre), if (sym.isModuleClass) sym.name.toTermName else sym.name)
    case TypeRef(pre, sym, args) =>
      AppliedTypeTree(treeForType(internal.typeRef(pre, sym, Nil)), args.map(treeForType))
    case ThisType(sym) if sym.isModuleClass =>
      pathTo(sym)
    case ThisType(sym) =>
      SingletonTypeTree(This(sym.name.toTypeName))
    case SingleType(NoPrefix, sym) =>
      SingletonTypeTree(Ident(sym.name))
    case SingleType(pre, sym) =>
      select(treeForType(pre), sym.name)
    case TypeBounds(lo, hi) =>
      TypeBoundsTree(treeForType(lo), treeForType(hi))
    case RefinedType(parents, scope) =>
      val defns = scope.iterator.filterNot(s => s.isMethod && s.asMethod.isSetter).map {
        case ts: TypeSymbol => typeSymbolToTypeDef(ts)
        case ms: MethodSymbol if ms.isGetter => getterSymbolToValDef(ms)
        case ms: MethodSymbol => methodSymbolToDefDef(ms)
      }.toList
      CompoundTypeTree(Template(parents.map(treeForType), noSelfType, defns))
    case ExistentialType(quantified, underlying) =>
      ExistentialTypeTree(treeForType(underlying), quantified.map {
        case ExistentialSingleton(sym, name, signature) => existentialSingletonToValDef(sym, name, signature)
        case sym => typeSymbolToTypeDef(sym)
      })
    case NullaryMethodType(resultType) =>
      treeForType(resultType)
    case AnnotatedType(annots, underlying) =>
      annots.foldLeft(treeForType(underlying))((tree, annot) => Annotated(annot.tree, tree))
    case _ =>
      throw new Exception("Cannot create a tree that would represent " + showRaw(tpe))
  }

  def methodSymbolToDefDef(sym: Symbol): DefDef = {
    val ms = sym.asMethod
    val mods = Modifiers(Flag.DEFERRED, typeNames.EMPTY, Nil)
    val sig = ms.typeSignature
    val typeParams = sig.typeParams.map(typeSymbolToTypeDef(_, forMethod = true))
    val paramss = sig.paramLists.map(_.map(paramSymbolToValDef))
    DefDef(mods, ms.name, typeParams, paramss, treeForType(sig.finalResultType), EmptyTree)
  }

  def paramSymbolToValDef(sym: Symbol): ValDef = {
    val ts = sym.asTerm
    val implicitFlag = if (sym.isImplicit) Flag.IMPLICIT else NoFlags
    val mods = Modifiers(Flag.PARAM | implicitFlag, typeNames.EMPTY, ts.annotations.map(_.tree))
    ValDef(mods, ts.name, treeForType(sym.typeSignature), EmptyTree)
  }

  def getterSymbolToValDef(sym: Symbol): ValDef = {
    val ms = sym.asMethod
    val mutableFlag = if (ms.isVar) Flag.MUTABLE else NoFlags
    val mods = Modifiers(Flag.DEFERRED | mutableFlag, typeNames.EMPTY, ms.annotations.map(_.tree))
    ValDef(mods, ms.name, treeForType(sym.typeSignature), EmptyTree)
  }

  def existentialSingletonToValDef(sym: Symbol, name: TermName, tpe: Type): ValDef = {
    val mods = Modifiers(Flag.DEFERRED, typeNames.EMPTY, sym.annotations.map(_.tree))
    ValDef(mods, name, treeForType(tpe), EmptyTree)
  }

  def typeSymbolToTypeDef(sym: Symbol, forMethod: Boolean = false): TypeDef = {
    val ts = sym.asType

    val paramOrDeferredFlag =
      if (ts.isParameter) Flag.PARAM
      else if (ts.isAbstract) Flag.DEFERRED
      else NoFlags
    val syntheticFlag = if (ts.isSynthetic) Flag.SYNTHETIC else NoFlags
    val varianceFlag =
      if (forMethod) NoFlags
      else if (ts.isCovariant) Flag.COVARIANT
      else if (ts.isContravariant) Flag.CONTRAVARIANT
      else NoFlags

    val flags = paramOrDeferredFlag | syntheticFlag | varianceFlag
    val mods = Modifiers(flags, typeNames.EMPTY, ts.annotations.map(_.tree))
    val (typeParams, signature) = sym.typeSignature match {
      case PolyType(polyParams, resultType) => (polyParams, resultType)
      case sig => (ts.typeParams, sig)
    }
    TypeDef(mods, ts.name, typeParams.map(typeSymbolToTypeDef(_)), treeForType(signature))
  }

  def alternatives(sym: Symbol): List[Symbol] = sym match {
    case ts: TermSymbol => ts.alternatives
    case NoSymbol => Nil
    case _ => List(sym)
  }

  def unwrapNullaryMt(tpe: Type): Type = tpe match {
    case NullaryMethodType(underlying) => unwrapNullaryMt(underlying)
    case _ => tpe
  }

  def isFirstListVarargs(meth: Symbol): Boolean =
    meth.typeSignature.paramLists.headOption.flatMap(_.lastOption).exists(isRepeated)

  def actualParamType(param: Symbol): Type =
    actualParamType(param.typeSignature)

  def actualParamType(tpe: Type): Type = tpe match {
    case TypeRef(_, s, List(arg)) if s == definitions.RepeatedParamClass =>
      getType(tq"$ScalaPkg.Seq[$arg]")
    case TypeRef(_, s, List(arg)) if s == definitions.ByNameParamClass =>
      arg
    case _ => tpe
  }

  def isRepeated(param: Symbol): Boolean =
    param.typeSignature.typeSymbol == definitions.RepeatedParamClass

  def isParameterless(signature: Type): Boolean =
    signature.paramLists.flatten == Nil

  def hasMemberWithSig(tpe: Type, name: Name, suchThat: Type => Boolean): Boolean =
    alternatives(tpe.member(name)).exists(sym => suchThat(sym.typeSignatureIn(tpe)))

  object SingleParamList {
    def unapply(paramLists: List[List[Symbol]]): Option[List[Symbol]] = paramLists match {
      case head :: tail if tail.flatten.forall(_.isImplicit) => Some(head)
      case _ => None
    }
  }

  def matchingApplyUnapply(tpe: Type, applySig: Type, unapplySig: Type): Boolean = {
    applySig.finalResultType =:= tpe && (unapplySig.paramLists match {
      case SingleParamList(List(unapplyParam)) if unapplyParam.typeSignature =:= tpe =>
        applySig.paramLists match {
          case SingleParamList(args) => isCorrectUnapply(unapplySig.finalResultType, args)
          case _ => false
        }
      case _ => false
    })
  }

  def isCorrectUnapply(unapplyResultType: Type, applyParams: List[Symbol], elemAdjust: Type => Type = identity): Boolean =
    unapplyResultType match {
      // https://issues.scala-lang.org/browse/SI-6541 (fixed in Scala 2.11.5 but requires -Xsource:2.12)
      case ExistentialType(quantified, underlying) =>
        isCorrectUnapply(underlying, applyParams, internal.existentialAbstraction(quantified, _))
      case tpe =>
        def hasIsEmpty = hasMemberWithSig(tpe, TermName("isEmpty"),
          sig => isParameterless(sig) && sig.finalResultType =:= typeOf[Boolean])

        def hasProperGet(resultTypeCondition: Type => Boolean): Boolean =
          hasMemberWithSig(tpe, TermName("get"), sig => isParameterless(sig) && resultTypeCondition(sig.finalResultType))

        applyParams match {
          case Nil =>
            tpe =:= typeOf[Boolean]
          case List(singleParam) =>
            hasIsEmpty && hasProperGet(resType => elemAdjust(resType) =:= actualParamType(singleParam.typeSignature))
          case params =>
            hasIsEmpty && hasProperGet { resType =>
              val elemTypes = Iterator.range(1, 22).map { i =>
                alternatives(resType.member(TermName(s"_$i")))
                  .map(_.typeSignatureIn(resType))
                  .find(sig => sig.typeParams == Nil && sig.paramLists == Nil)
                  .map(sig => elemAdjust(sig.finalResultType))
                  .getOrElse(NoType)
              }.takeWhile(_ != NoType).toList

              def check(params: List[Symbol], elemTypes: List[Type]): Boolean = (params, elemTypes) match {
                case (Nil, Nil) => true
                case (p :: prest, et :: etrest) =>
                  actualParamType(p.typeSignature) =:= et && check(prest, etrest)
                case _ => false
              }

              check(params, elemTypes)
            }
        }
    }

  case class ApplyUnapply(apply: Symbol, unapply: Symbol, params: List[(TermSymbol, Tree)])

  def applyUnapplyFor(tpe: Type): Option[ApplyUnapply] = {
    val dtpe = tpe.dealias
    companionOf(dtpe).map(c.typecheck(_)).flatMap(comp => applyUnapplyFor(dtpe, comp))
  }

  def applyUnapplyFor(tpe: Type, companion: Tree): Option[ApplyUnapply] = {
    val dtpe = tpe.dealias
    val ts = dtpe.typeSymbol.asType
    val caseClass = ts.isClass && ts.asClass.isCaseClass

    val applyUnapplyPairs = for {
      apply <- alternatives(companion.tpe.member(TermName("apply")))
      unapplyName = if (isFirstListVarargs(apply)) "unapplySeq" else "unapply"
      unapply <- alternatives(companion.tpe.member(TermName(unapplyName)))
    } yield (apply, unapply)

    def setTypeArgs(sig: Type) = sig match {
      case PolyType(params, resultType) => resultType.substituteTypes(params, dtpe.typeArgs)
      case _ => sig
    }

    def typeParamsMatch(apply: Symbol, unapply: Symbol) = {
      val expected = dtpe.typeArgs.length
      apply.typeSignature.typeParams.length == expected && unapply.typeSignature.typeParams.length == expected
    }

    val applicableResults = applyUnapplyPairs.flatMap {
      case (apply, unapply) if typeParamsMatch(apply, unapply) =>
        val constructor =
          if (caseClass && apply.isSynthetic)
            alternatives(dtpe.member(termNames.CONSTRUCTOR)).find(_.asMethod.isPrimaryConstructor).getOrElse(NoSymbol)
          else NoSymbol

        val applySig = if (constructor != NoSymbol) constructor.typeSignatureIn(dtpe) else setTypeArgs(apply.typeSignatureIn(companion.tpe))
        val unapplySig = setTypeArgs(unapply.typeSignatureIn(companion.tpe))

        if (matchingApplyUnapply(dtpe, applySig, unapplySig)) {
          def defaultValueFor(param: Symbol, idx: Int): Tree =
            if (param.asTerm.isParamWithDefault) {
              val methodEncodedName = param.owner.name.encodedName.toString
              q"$companion.${TermName(s"$methodEncodedName$$default$$${idx + 1}")}[..${tpe.typeArgs}]"
            }
            else EmptyTree

          val paramsWithDefaults = applySig.paramLists.head.zipWithIndex
            .map({ case (p, i) => (p.asTerm, defaultValueFor(p, i)) })
          Some(ApplyUnapply(constructor orElse apply, unapply, paramsWithDefaults))
        } else None

      case _ => None
    }

    applicableResults match {
      case List(result) => Some(result)
      case _ => None
    }
  }

  def singleValueFor(tpe: Type): Option[Tree] = tpe match {
    case ThisType(sym) if enclosingClasses.contains(sym) =>
      Some(This(sym))
    case ThisType(sym) if sym.isModuleClass =>
      singleValueFor(internal.thisType(sym.owner)).map(pre => Select(pre, tpe.termSymbol))
    case ThisType(sym) =>
      Some(This(sym))
    case SingleType(pre, sym) =>
      singleValueFor(pre).map(prefix => Select(prefix, sym))
    case ConstantType(value) =>
      Some(Literal(value))
    case TypeRef(pre, sym, Nil) if sym.isModuleClass =>
      singleValueFor(pre).map(prefix => Select(prefix, sym.asClass.module))
    case _ =>
      None
  }

  def companionOf(tpe: Type): Option[Tree] = tpe match {
    case TypeRef(pre, sym, _) if sym.companion != NoSymbol =>
      singleValueFor(pre).map(Select(_, sym.companion)) orElse singleValueFor(tpe.companion)
    case _ => singleValueFor(tpe.companion)
  }

  def typeOfTypeSymbol(sym: TypeSymbol) = sym.toType match {
    case t@TypeRef(pre, s, Nil) if t.takesTypeArgs =>
      internal.typeRef(pre, s, t.typeParams.map(ts => internal.typeRef(NoPrefix, ts, Nil)))
    case t => t
  }

  def isSealedHierarchyRoot(sym: Symbol): Boolean =
    sym.isClass && sym.isAbstract && sym.asClass.isSealed

  def knownNonAbstractSubclasses(sym: Symbol): Set[Symbol] =
    sym.asClass.knownDirectSubclasses.flatMap { s =>
      if (isSealedHierarchyRoot(s)) knownNonAbstractSubclasses(s) else Set(s)
    }

  def allCurrentlyKnownSubclasses(sym: Symbol): Set[Symbol] =
    if (sym.isClass) {
      val directSubclasses = sym.asClass.knownDirectSubclasses
      directSubclasses.flatMap(allCurrentlyKnownSubclasses) + sym
    } else Set.empty

  def knownSubtypes(tpe: Type): Option[List[Type]] = {
    val dtpe = tpe.dealias
    Option(dtpe.typeSymbol).filter(isSealedHierarchyRoot).map { sym =>
      knownNonAbstractSubclasses(sym).toList.flatMap { subSym =>
        val undetparams = subSym.asType.typeParams
        val undetSubTpe = typeOfTypeSymbol(subSym.asType)

        if (undetparams.nonEmpty)
          determineTypeParams(undetSubTpe, dtpe, undetparams)
            .map(typeArgs => undetSubTpe.substituteTypes(undetparams, typeArgs))
        else if (undetSubTpe <:< dtpe)
          Some(undetSubTpe)
        else
          None
      }
    }
  }

  def determineTypeParams(undetTpe: Type, detTpe: Type, typeParams: List[Symbol]): Option[List[Type]] = {
    val methodName = c.freshName(TermName("m"))
    val typeDefs = typeParams.map(typeSymbolToTypeDef(_, forMethod = true))

    val tree = c.typecheck(
      q"""
        def $methodName[..$typeDefs](f: ${treeForType(undetTpe)} => $UnitCls): $UnitCls = ()
        $methodName((_: ${treeForType(detTpe)}) => ())
      """, silent = true
    )

    tree match {
      case Block(_, Apply(TypeApply(_, args), _)) => Some(args.map(_.tpe))
      case Block(_, Apply(_, _)) => Some(Nil)
      case EmptyTree => None
    }
  }

  def abortOnTypecheckException[T](expr: => T): T =
    try expr catch {
      case TypecheckException(pos, msg) => abort(msg)
    }

  def typecheckException(msg: String) =
    throw TypecheckException(c.enclosingPosition, msg)

  def isTuple(sym: Symbol) =
    definitions.TupleClass.seq.contains(sym)
}

abstract class AbstractMacroCommons(val c: blackbox.Context) extends MacroCommons
