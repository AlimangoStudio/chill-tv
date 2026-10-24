// Chill TV Showcase: your business details and media. Edit this file, put your pictures, videos and
// music in this folder (app/assets), then build. Every file name below is relative to this folder.
var CHILL = {
  // Shown on the idle showcase, the reel screen and the remote.
  brand: {
    name: 'Your Business',
    tagline: 'Your tagline here',
    initial: 'Y',                       // the letter inside the round logo
    website: 'yourwebsite.com',
    cta: 'Ask our team',                // the button on each idle slide
    ctaNote: 'for more information today'
  },

  // Weather and clock. Leave lat/lon null to show the clock only. timeZone '' = the TV's own time zone.
  // Find coordinates at https://open-meteo.com/en/docs/geocoding-api
  location: { place: 'Your City', lat: null, lon: null, timeZone: '' },

  // Rotating slides: the home screen background colours and the idle showcase text.
  // tint: t1 teal, t2 gold, t3 mint, t4 rose, t5 green.
  slides: [
    { tint: 't1', kicker: 'Featured', title: 'Your Product', line: 'One line about it', long: 'A longer sentence about your product or service.' },
    { tint: 't2', kicker: 'Popular', title: 'Your Service', line: 'What makes it special', long: 'A longer sentence about what customers love.' },
    { tint: 't3', kicker: 'New', title: 'Something New', line: 'Just arrived', long: 'Tell people about what is new this month.' },
    { tint: 't4', kicker: 'Offer', title: 'This Month', line: 'Your promotion here', long: 'Describe this month\'s offer in a sentence.' }
  ],

  // Middle card on the home screen: your ad pictures, 16:9 (1600×900 is ideal), shown in turn.
  // Empty list = the coloured slides only.
  ads: ['sample-ad1.jpg', 'sample-ad2.jpg', 'sample-ad3.jpg'],

  // Idle showcase as finished full-screen pictures (1920×1080), one per slide above, in the same order.
  // Empty list = the designed text slides.
  idlePictures: [],

  // Videos for the reel screen (vertical 9:16 looks best), played in order, then back home.
  // Empty list = no reel tile on the home screen.
  reelLabel: 'Our Reel',
  reelThumb: '',                        // optional 16:9 picture for the reel tile
  reels: [
    // { src: 'reel1.mp4', kicker: 'Featured', title: 'Your Product', line: 'Ask us about it today' }
  ],

  // Background music, looped quietly; pauses for the reel and while another app is open.
  // Only use music you have the rights to; the credit line shows for a few seconds when each track starts.
  musicVolume: 0.35,
  // Included: six calm classical pieces (public domain or CC BY; see README). Replace or add your own.
  music: [
    { src: 'music-canon-in-d.m4a', title: 'Canon in D', by: 'Pachelbel · Kevin MacLeod (incompetech.com), CC BY 3.0' },
    { src: 'music-gymnopedie-1.m4a', title: 'Gymnopédie No. 1', by: 'Satie · public domain (CC0)' },
    { src: 'music-clair-de-lune.m4a', title: 'Clair de lune', by: 'Debussy · Laurens Goedhart, public domain' },
    { src: 'music-gymnopedie-2.m4a', title: 'Gymnopédie No. 2', by: 'Satie · Michael Laucke, public domain' },
    { src: 'music-gnossienne-1.m4a', title: 'Gnossienne No. 1', by: 'Satie · La Pianista, public domain' },
    { src: 'music-chopin-nocturne-op32-2.m4a', title: 'Nocturne Op. 32 No. 2', by: 'Chopin · Olga Gurevich (Musopen), public domain' }
  ],

  idleMinutes: 3,                       // home → idle showcase after this long without a key press
  slideSeconds: 8
};
