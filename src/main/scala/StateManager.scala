import scodec.bits.ByteVector

import codecs._

case class StateManager(
    peerId: ByteVector,
    lcssCurrent: LastCrossSignedState,
    uncommittedUpdates: List[FromLocal | FromRemote] = List.empty
) {
  // return a copy of this state with the update_add_htlc/update_fail_htlc/update_fulfill_htlc
  // appended to the list of uncommitted updates that will be used to generate lcssNext below
  // and that will be processed and dispatched to our upstream node once they are actually committed
  // TODO: should we also not add if this is an htlc that is already committed? probably
  def addUncommittedUpdate(upd: FromLocal | FromRemote): StateManager = {
    if (this.uncommittedUpdates.exists(_ == upd)) then this
    else this.copy(uncommittedUpdates = this.uncommittedUpdates :+ upd)
  }
  def removeUncommitedUpdate(upd: FromLocal | FromRemote): StateManager =
    this.copy(uncommittedUpdates = this.uncommittedUpdates.filterNot(_ == upd))

  // calculates what will be our next state once we commit these uncommitted updates
  lazy val lcssNext: LastCrossSignedState = {
    val base =
      lcssCurrent.copy(
        blockDay = 0,
        remoteSigOfLocal = ByteVector64.Zeroes,
        localSigOfRemote = ByteVector64.Zeroes
      )

    uncommittedUpdates
      .foldLeft(base)((lcss, upd) =>
        upd match {
          case FromRemote(add: UpdateAddHtlc) =>
            lcss.copy(
              remoteBalanceMsat = lcss.remoteBalanceMsat - add.amountMsat,
              remoteUpdates = lcss.remoteUpdates + 1,
              incomingHtlcs = lcss.incomingHtlcs :+ add
            )
          case FromRemote(
                fail: (UpdateFailHtlc | UpdateFailMalformedHtlc)
              ) => {
            val htlcId = fail match {
              case x: UpdateFailHtlc          => x.id;
              case x: UpdateFailMalformedHtlc => x.id
            }

            lcss.outgoingHtlcs.find(_.id == htlcId) match {
              case Some(htlc) => {
                lcss.copy(
                  localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                  remoteUpdates = lcss.remoteUpdates + 1,
                  outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
          case FromRemote(fulfill: UpdateFulfillHtlc) => {
            lcss.outgoingHtlcs.find(_.id == fulfill.id) match {
              case Some(htlc) => {
                lcss.copy(
                  remoteBalanceMsat = lcss.remoteBalanceMsat + htlc.amountMsat,
                  remoteUpdates = lcss.remoteUpdates + 1,
                  outgoingHtlcs = lcss.outgoingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
          case FromLocal(add: UpdateAddHtlc, _) => {
            lcss.copy(
              localBalanceMsat = lcss.localBalanceMsat - add.amountMsat,
              localUpdates = lcss.localUpdates + 1,
              outgoingHtlcs = lcss.outgoingHtlcs :+ add
            )
          }
          case FromLocal(
                fail: (UpdateFailHtlc | UpdateFailMalformedHtlc),
                _
              ) => {
            val htlcId = fail match {
              case x: UpdateFailHtlc          => x.id;
              case x: UpdateFailMalformedHtlc => x.id
            }

            lcss.incomingHtlcs.find(_.id == htlcId) match {
              case Some(htlc) => {
                lcss.copy(
                  remoteBalanceMsat = lcss.remoteBalanceMsat + htlc.amountMsat,
                  localUpdates = lcss.localUpdates + 1,
                  incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
          case FromLocal(fulfill: UpdateFulfillHtlc, _) => {
            lcss.incomingHtlcs.find(_.id == fulfill.id) match {
              case Some(htlc) => {
                lcss.copy(
                  localBalanceMsat = lcss.localBalanceMsat + htlc.amountMsat,
                  localUpdates = lcss.localUpdates + 1,
                  incomingHtlcs = lcss.incomingHtlcs.filterNot(_ == htlc)
                )
              }
              case None => lcss
            }
          }
        }
      )
  }
}
