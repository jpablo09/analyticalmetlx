package com.metl.model

import com.metl.data.{Group=>MeTLGroup,_}
import com.metl.utils._
import com.metl.renderer.SlideRenderer

import scala.xml._
import net.liftweb._
import actor._
import common._
import http._
import util._
import util.TimeHelpers
import Helpers._
import java.util.Date
import com.metl.renderer.RenderDescription

import scala.collection.mutable.Queue

abstract class RoomProvider {
  def get(jid:String):MeTLRoom
  def get(jid:String,roomMetaData:RoomMetaData):MeTLRoom
  def removeMeTLRoom(room:String):Unit
  def exists(room:String):Boolean
}

object EmptyRoomProvider extends RoomProvider {
  override def get(jid:String) = EmptyRoom
  override def get(jid:String,roomDefinition:RoomMetaData) = EmptyRoom
  override def removeMeTLRoom(room:String) = {}
  override def exists(room:String) = false
}

object MeTLRoomType extends Enumeration {
  type MeTLRoomType = Value
  val Conversation,Slide,PrivateSlide,PersonalChatroom,Global,Unknown = Value
}

abstract class RoomMetaData(val server:String, val roomType:MeTLRoomType.Value) {
  def getJid:String
}

case class ConversationRoom(override val server:String,jid:String) extends RoomMetaData(server,MeTLRoomType.Conversation){
  def cd:Conversation = ServerConfiguration.configForName(server).detailsOfConversation(jid.toString)
  override def getJid = jid
}
case class SlideRoom(override val server:String,jid:String,slideId:Int) extends RoomMetaData(server,MeTLRoomType.Slide){
  def cd:Conversation = ServerConfiguration.configForName(server).detailsOfConversation(jid.toString)
  def s:Slide = cd.slides.find(_.id == slideId).getOrElse(Slide.empty)
  override def getJid = slideId.toString
}
case class PrivateSlideRoom(override val server:String,jid:String,slideId:Int,owner:String) extends RoomMetaData(server,MeTLRoomType.PrivateSlide){
  def cd:Conversation = ServerConfiguration.configForName(server).detailsOfConversation(jid.toString)
  def s:Slide = cd.slides.find(_.id == slideId).getOrElse(Slide.empty)
  override def getJid = slideId.toString + owner
}
case class PersonalChatRoom(override val server:String,owner:String) extends RoomMetaData(server,MeTLRoomType.PersonalChatroom){
  override def getJid = owner
}
case class GlobalRoom(override val server:String) extends RoomMetaData(server,MeTLRoomType.Global){
  override def getJid = "global"
}
case object UnknownRoom extends RoomMetaData(EmptyBackendAdaptor.name,MeTLRoomType.Unknown){
  override def getJid = ""
}
object RoomMetaDataUtils {
  protected val numerics = Range('0','9').toList
  protected def splitJid(in:String):Tuple2[Int,String] = {
    val first = in.takeWhile(i => numerics.contains(i)).toString
    val second = in.dropWhile(i => numerics.contains(i)).toString
    val jid = try {
      first.toInt
    } catch {
      case e:Exception => 0
    }
    (jid,second)
  }
  def fromJid(jid:String,server:String = ServerConfiguration.default.name):RoomMetaData = {
    splitJid(jid) match {
      case (0,"") => UnknownRoom
      case (0,"global") => GlobalRoom(server)
      case (conversationJid,"") if (conversationJid % 1000 == 0) => ConversationRoom(server,conversationJid.toString)
      case (slideJid,"") => SlideRoom(server,(slideJid - (slideJid % 1000)).toString,slideJid)
      case (0,user) => PersonalChatRoom(server,user)
      case (slideJid,user) => PrivateSlideRoom(server,(slideJid - (slideJid % 1000)).toString,slideJid,user)
      case _ => UnknownRoom
    }
  }
}

class HistoryCachingRoomProvider(configName:String) extends RoomProvider {
  private lazy val metlRooms = new SynchronizedWriteMap[String,MeTLRoom](scala.collection.mutable.HashMap.empty[String,MeTLRoom],true,(k:String) => createNewMeTLRoom(k,UnknownRoom))
  override def exists(room:String):Boolean = Stopwatch.time("Rooms.exists", () => metlRooms.keys.exists(k => k == room))
  override def get(room:String) = Stopwatch.time("Rooms.get", () => metlRooms.getOrElseUpdate(room, createNewMeTLRoom(room,UnknownRoom)))
  override def get(room:String,roomDefinition:RoomMetaData) = Stopwatch.time("Rooms.get", () => metlRooms.getOrElseUpdate(room, createNewMeTLRoom(room,roomDefinition)))
  protected def createNewMeTLRoom(room:String,roomDefinition:RoomMetaData) = Stopwatch.time("Rooms.createNewMeTLRoom(%s)".format(room), () => {
    //val r = new HistoryCachingRoom(configName,room,this,roomDefinition)
    val r = new XmppBridgingHistoryCachingRoom(configName,room,this,roomDefinition)
    r.localSetup
    r
  })
  override def removeMeTLRoom(room:String) = Stopwatch.time("Rooms.removeMeTLRoom(%s)".format(room), () => {
    if (exists(room)){
      metlRooms(room).localShutdown
      metlRooms.remove(room)
    }
  })
}


case class ServerToLocalMeTLStanza[A <: MeTLStanza](stanza:A)
case class LocalToServerMeTLStanza[A <: MeTLStanza](stanza:A)
abstract class RoomStateInformation
case class RoomJoinAcknowledged(server:String,room:String) extends RoomStateInformation
case class RoomLeaveAcknowledged(server:String,room:String) extends RoomStateInformation
case class JoinRoom(username:String,cometId:String,actor:LiftActor)
case class LeaveRoom(username:String,cometId:String,actor:LiftActor)

case object HealthyWelcomeFromRoom
case object Ping

abstract class MeTLRoom(configName:String,val location:String,creator:RoomProvider,val roomMetaData:RoomMetaData) extends LiftActor with ListenerManager {
  lazy val config = ServerConfiguration.configForName(configName)
  private var shouldBacklog = false
  private var backlog = Queue.empty[MeTLStanza]
  private def onConnectionLost:Unit = {
    //println("MeTLRoom(%s):onConnectionLost".format(location))
    shouldBacklog = true
  }
  private def onConnectionRegained:Unit = {
    //println("MeTLRoom(%s):onConnectionRegained".format(location))
    initialize
    processBacklog
    shouldBacklog = false
  }
  private def processBacklog:Unit = {
    //println("MeTLRoom(%s):sendToServer.processingBacklog".format(location))
    while (!backlog.isEmpty){
      val item = backlog.dequeue
      //println("MeTLRoom(%s):sendToServer.processingBacklog.dequeue(%s)".format(location,item))
      this ! LocalToServerMeTLStanza(item)
    }
  }
  protected def initialize:Unit = {}
  protected val messageBusDefinition = new MessageBusDefinition(location,"unicastBackToOwner",(s:MeTLStanza) => this ! ServerToLocalMeTLStanza(s),onConnectionLost _,onConnectionRegained _)
  protected val messageBus = config.getMessageBus(messageBusDefinition)
  def getHistory:History
  def getThumbnail:Array[Byte]
  def getSnapshot(size:RenderDescription):Array[Byte]

  // this is the bit which needs tweaking now - it looks rather a lot like the members are already in the history, and what I should be checking is the slides, which may mean checking conversationDetails a little more frequently.  Fortunately, they're cached, so it shouldn't be expensive.
  def getAttendance:List[String] = {
    roomMetaData match {
      case cr:ConversationRoom => {
        val attendance = cr.cd.slides.flatMap(_.groupSet.flatMap(_.groups.flatMap(_.members)))
        println("known members: %s".format(attendance))
        attendance
      }
      case _ => List.empty[String]
    }
  }
  def getAttendances:List[Attendance] = {
    getHistory.getAttendances
  }
  def getGroupSets:List[GroupSet] = {
    roomMetaData match {
      case cr:ConversationRoom => cr.cd.slides.flatMap(s => s.groupSet).toList
      case s:SlideRoom => s.s.groupSet.toList
      case _ => Nil
    }
  }
  def updateGroupSets:Option[Conversation] = {
    roomMetaData match {
      case cr:ConversationRoom => {
        println("updating conversationRoom: %s".format(cr))
        val details = cr.cd
        val a = getAttendances.map(_.author)
        val newSlides = details.slides.map(slide => {
          slide.copy(groupSet = slide.groupSet.map(gs => {
            val grouped = gs.groups.flatMap(g => g.members)
            val ungrouped = a.filterNot(m => grouped.contains(m))
            ungrouped.foldLeft(gs.copy())((groupSet,person) => groupSet.groupingStrategy.addNewPerson(groupSet,person))
          }))
        })
        println("newSlides: %s".format(newSlides))
        Some(cr.cd.copy(slides = newSlides))
      }
      case _ => None
    }
  }
  protected val pollInterval = new TimeSpan(120000)
  protected var joinedUsers = List.empty[Tuple3[String,String,LiftActor]]
  def createUpdate = HealthyWelcomeFromRoom
  protected var lastInterest:Long = new Date().getTime
  protected var interestTimeout:Long = 60000
  protected def heartbeat = ActorPing.schedule(this,Ping,pollInterval)
  def localSetup = {
    heartbeat
  }
  def localShutdown = {
    //println("MeTLRoom(%s):localShutdown".format(location))
    messageBus.release
  }
  initialize
  case object IrrelevantMatch
  protected def overrideableLowPriority:PartialFunction[Any,Unit] = {
    case IrrelevantMatch => {}
  }
  override def lowPriority:PartialFunction[Any,Unit] = coreLowPriority orElse overrideableLowPriority orElse catchAll
  protected def coreLowPriority:PartialFunction[Any,Unit] = {
    case j:JoinRoom => Stopwatch.time("MeTLRoom.lowPriority.JoinRoom", () => addConnection(j))
    case l:LeaveRoom => Stopwatch.time("MeTLRoom.lowPriority.LeaveRoom", () => removeConnection(l))
    case sl@ServerToLocalMeTLStanza(s) => Stopwatch.time("MeTLRoom.lowPriority.ServerToLocalMeTLStanza", () => sendToChildren(s))
    case Ping => Stopwatch.time("MeTLRoom.ping", () => {
      if (possiblyCloseRoom){

      } else {
        heartbeat
      }
    })
    case ls@LocalToServerMeTLStanza(s) => Stopwatch.time("MeTLRoom.lowPriority.LocalToServerMeTLStanza", () => {
      println("received stanza to send to server: %s %s".format(ls, s))
      sendStanzaToServer(s)
    })
  }
  protected def catchAll:PartialFunction[Any,Unit] = {
    case _ => println("MeTLRoom recieved unknown message")
  }
  def getChildren:List[Tuple3[String,String,LiftActor]] = Stopwatch.time("MeTLRoom.getChildren",() => {
    joinedUsers.toList
  })
  protected def sendToChildren(a:MeTLStanza):Unit = Stopwatch.time("MeTLRoom.sendToChildren",() => {
    println("stanza received: %s".format(a))
    (a,roomMetaData) match {
      case (m:Attendance,cr:ConversationRoom) => {
        println("attendance received: %s".format(m))
  //      if (!getAttendance.exists(_ == m.author)){
          updateGroupSets.foreach(c => {
            println("updated conversation postGroupsUpdate: %s".format(c))
            config.updateConversation(c.jid.toString,c)
          })
  //      }
      }
      /*
      case (m:MeTLCommand,cr:GlobalRoom) if m.command == "/UPDATE_CONVERSATION_DETAILS" => {
        println("updating conversation details")
        cr.cd = config.detailsOfConversation(cr.jid)
      }
      */
      case _ => {}
    }
    //println("%s s->l %s".format(location,a))
    //println("MeTLRoom(%s):sendToChildren(%s)".format(location,a))
    joinedUsers.foreach(j => j._3 ! a)
  })
  protected def sendStanzaToServer(s:MeTLStanza):Unit = Stopwatch.time("MeTLRoom.sendStanzaToServer", () => {
    //println("%s l->s %s".format(location,s))
    //println("MeTLRoom(%s):sendToServer(%s)".format(location,s))
    //println("%s received stanza to send: %s".format(location,s))
    showInterest
    if (shouldBacklog) {
      //println("MeTLRoom(%s):sendToServer.backlogging".format(location))
      backlog.enqueue(s)
    } else {
      println("sendingStanzaToServer: %s".format(s))
      messageBus.sendStanzaToRoom(s)
    }
  })
  private def formatConnection(username:String,uniqueId:String):String = "%s_%s".format(username,uniqueId)
  private def addConnection(j:JoinRoom):Unit = Stopwatch.time("MeTLRoom.addConnection(%s)".format(j),() => {
    joinedUsers = ((j.username,j.cometId,j.actor) :: joinedUsers).distinct
    j.actor ! RoomJoinAcknowledged(configName,location)
    showInterest
  })
  private def removeConnection(l:LeaveRoom):Unit = Stopwatch.time("MeTLRoom.removeConnection(%s)".format(l), () => {
    joinedUsers = joinedUsers.filterNot(i => i._1 == l.username && i._2 == l.cometId)
    l.actor ! RoomLeaveAcknowledged(configName,location)
  })
  private def possiblyCloseRoom:Boolean = Stopwatch.time("MeTLRoom.possiblyCloseRoom", () => {
    if (location != "global" && joinedUsers.length == 0 && !recentInterest) {
      //println("MeTLRoom(%s):heartbeat.closingRoom".format(location))
      creator.removeMeTLRoom(location)
      true
    } else {
      false
    }
  })
  protected def showInterest:Unit = lastInterest = new Date().getTime
  private def recentInterest:Boolean = Stopwatch.time("MeTLRoom.recentInterest", () => {
    (new Date().getTime - lastInterest) < interestTimeout
  })
  override def toString = "MeTLRoom(%s,%s,%s)".format(configName,location,creator)
}

object EmptyRoom extends MeTLRoom("empty","empty",EmptyRoomProvider,UnknownRoom) {
  override def getHistory = History.empty
  override def getThumbnail = Array.empty[Byte]
  override def getSnapshot(size:RenderDescription) = Array.empty[Byte]
  override protected def sendToChildren(s:MeTLStanza) = {}
  override protected def sendStanzaToServer(s:MeTLStanza) = {}
}

object ThumbnailSpecification {
  val height = 240
  val width = 320
}

class NoCacheRoom(configName:String,override val location:String,creator:RoomProvider,override val roomMetaData:RoomMetaData) extends MeTLRoom(configName,location,creator,roomMetaData) {
  override def getHistory = config.getHistory(location)
  override def getThumbnail = {
    roomMetaData match {
      case s:SlideRoom => {
        SlideRenderer.render(getHistory,Globals.ThumbnailSize,"presentationSpace")
      }
      case _ => {
        Array.empty[Byte]
      }
    }
  }
  override def getSnapshot(size:RenderDescription) = {
    roomMetaData match {
      case s:SlideRoom => {
        SlideRenderer.render(getHistory,size,"presentationSpace")
      }
      case _ => {
        Array.empty[Byte]
      }
    }
  }
}

case object ThumbnailRenderRequest

class StartupInformation {
  private var isFirstTime = true
  def setHasStarted(value:Boolean):Unit = {
    isFirstTime = value
  }
  def getHasStarted:Boolean = {
    if (isFirstTime)
      true
    else false
  }
}

class HistoryCachingRoom(configName:String,override val location:String,creator:RoomProvider,override val roomMetaData:RoomMetaData) extends MeTLRoom(configName,location,creator,roomMetaData) {
  private var history:History = History.empty
  private val isPublic = tryo(location.toInt).map(l => true).openOr(false)
  private var snapshots:Map[RenderDescription,Array[Byte]] = Map.empty[RenderDescription,Array[Byte]]
  private lazy val starting = new StartupInformation
  private def firstTime = initialize
  override def initialize = Stopwatch.time("HistoryCachingRoom.initalize",() => {
    if (starting.getHasStarted) {
      starting.setHasStarted(false)
    } else {
      showInterest
      history = config.getHistory(location).attachRealtimeHook((s) => {
        println("ROOM %s sending %s to children %s".format(location,s,joinedUsers))
        super.sendToChildren(s)
      })
      updateSnapshots
    }
  })
  firstTime
  private var lastRender = 0L
  private val acceptableRenderStaleness = 10000L
  override def getHistory:History = {
    showInterest
    history
  }
  private def updateSnapshots = Stopwatch.time("HistoryCachingRoom.updateSnapshots", () => {
    snapshots = makeSnapshots
    lastRender = history.lastVisuallyModified
  })
  private def makeSnapshots = Stopwatch.time("HistoryCachingRoom_%s@%s makingSnapshots".format(location,configName), () => {
    roomMetaData match {
      case s:SlideRoom => {
        val thisHistory = isPublic match {
          case true => history.filterCanvasContents(cc => cc.privacy == Privacy.PUBLIC)
          case false => history
        }
        SlideRenderer.renderMultiple(thisHistory,Globals.snapshotSizes)
      }
      case _ => {
        Map.empty[RenderDescription,Array[Byte]]
      }
    }
  })
  override def getSnapshot(size:RenderDescription) = {
    showInterest
    snapshots.get(size).getOrElse(Array.empty[Byte])
  }
  override def getThumbnail = {
    getSnapshot(Globals.ThumbnailSize)
  }
  private var renderInProgress = false;
  override def overrideableLowPriority = {
    case ThumbnailRenderRequest => {
      if ((new Date().getTime - lastRender) > acceptableRenderStaleness){
        renderInProgress = false
        updateSnapshots
      } else if (!renderInProgress){
        renderInProgress = true
        ActorPing.schedule(this,ThumbnailRenderRequest,acceptableRenderStaleness)
      }
    }
  }
  override protected def sendToChildren(s:MeTLStanza):Unit = Stopwatch.time("HistoryCachingMeTLRoom.sendToChildren",() => {
    history.addStanza(s)
    s match {
      case c:MeTLCanvasContent if (history.lastVisuallyModified > lastRender) => {
        this ! ThumbnailRenderRequest
      }
      case _ => {}
    }
  })
  override def toString = "HistoryCachingRoom(%s,%s,%s)".format(configName,location,creator)
}

class XmppBridgingHistoryCachingRoom(configName:String,override val location:String,creator:RoomProvider,override val roomMetaData:RoomMetaData) extends HistoryCachingRoom(configName,location,creator,roomMetaData) {
  protected var stanzasToIgnore = List.empty[MeTLStanza]
  def sendMessageFromBridge(s:MeTLStanza):Unit = Stopwatch.time("XmppBridgedHistoryCachingRoom.sendMessageFromBridge", () => {
    //stanzasToIgnore = stanzasToIgnore ::: List(s)
    println("xmppBridge, sending to server: %s".format(s))
    sendStanzaToServer(s)
  })
  protected def sendMessageToBridge(s:MeTLStanza):Unit = Stopwatch.time("XmppBridgedHistoryCachingROom.sendMessageFromBridge", () => {
    MeTLXConfiguration.xmppServer.foreach(_.relayMessageToXmppMuc(location,s))
  })
  override protected def sendToChildren(s:MeTLStanza):Unit = Stopwatch.time("XmppBridgedHistoryCachingRoom.sendToChildren", () => {
    val (matches,remaining) = stanzasToIgnore.partition(sti => sti.equals(s))
    matches.length match {
      case 1 => stanzasToIgnore = remaining
      case i:Int if i > 1 => stanzasToIgnore = remaining ::: matches.drop(1)
      case _ => sendMessageToBridge(s)
    }
    super.sendToChildren(s)
  })
}
