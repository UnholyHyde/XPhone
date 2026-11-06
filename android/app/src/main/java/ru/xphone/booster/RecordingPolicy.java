package ru.xphone.booster;
final class RecordingPolicy {
    static boolean valid(String filename,int mbps){return filename!=null&&filename.matches("XPhone-[0-9]{8}-[0-9]{6}\\.mp4")&&mbps>=4&&mbps<=30;}
}
