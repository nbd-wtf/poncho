poncho
======

## provider of credit-based channels for lightning nodes

![](https://i.pinimg.com/originals/63/6b/46/636b46410c8e0166bb6d8fe20dbe23f5.jpg)

This is an early alpha product.

### Operation

The CLN plugin provides these RPC methods:

- `hc-list`: lists all hosted channels.
- `hc-channel <peerid>`: shows just the channel specific for the given peer.
- `hc-override <peerid> <msatoshi>`: if the channel for this peer is in an error state, proposes overriding it to a new state in which the local balance is the given.

### Storage

When running on CLN, the plugin will create a folder at `$LIGHTNING_DIR/bitcoin/poncho/` and fill it with the following files:

```
~/.lightning/bitcoin/poncho/
├── channels
│   ├── 02dcfffc447b201bd83fa0a359f62acf33206f43c83aaeafc6082ac2e245f775a0.json
│   ├── 03ff908df11aef1b5fcf16c015ab28cab0bdb81d221a65065d7cf59dc59652af95.json
│   ├── 025964eb1b5cf60de72447b87eee7fe987a68e51d71a4bb2919a7ac06817b6b51e.json
│   └── 03362a35434a25651c05dbf3960f39a55084adada0dcac64f1744f4ab6fa6861e9.json
├── htlc-forwards.json
└── preimages.json
```

Each channel's `last_cross_signed_state` and other metadata is stored in a file under `channels/` named with the pubkey of remote peer. The other two files are helpers that should be empty most of the time and only have values in them while payments are in flight (so they can be recovered after a restart).

### Configuration

You can write a file at `$LIGHTNING_DIR/bitcoin/poncho/config.json` with the following properties, all optional. All amounts are in millisatoshis. The defaults are given below:

```json
{
  "cltvExpiryDelta": "143",
  "feeBase": 1000,
  "feeProportionalMillionths": 1000,
  "maxHtlcValueInFlightMsat": 100000000,
  "htlcMinimumMsat": 1000,
  "maxAcceptedHtlcs": 12,
  "channelCapacityMsat": 100000000,
  "initialClientBalanceMsat": 0,

  "contactURL": "",
  "logoFile": "",
  "hexColor": "#ffffff"
}
```

The branding information won't be used unless contact URL and logo file are set. The logo should be a PNG file also placed under `$LIGHTNING_DIR/bitcoin/poncho/` and specified as a relative path.

### FAQ

- **What happens if I lose the channel states?**

If you ever lose a channel cross-signed state, the client, when reconnecting, will automatically send you their latest state and you will accept those -- as they would be signed by your public key --, trusting that they are indeed the latest states, and store them.

If a client sends a state with an invalid signature it won't be accepted. If you have the latest state and the client sends a state with a smaller update number it also won't be accepted.
