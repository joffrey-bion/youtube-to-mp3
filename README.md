# YouTube to mp3

A tool to download the audio track of YouTube videos as mp3.

It uses the website https://ytmp3s.nu behind the scenes.

## How to use

To get the list of YouTube video URLs, go to a YouTube playlist page, and run this JS in the console:

```javascript
document.querySelectorAll("a.ytd-playlist-video-renderer").forEach((e) => console.log(e.attributes["href"].value))
```

Put the list of URLs into a text file, one per line. Then run this program with the file path as argument.
