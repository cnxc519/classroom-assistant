package cn.classroom.assistant;

import android.content.Context;
import java.io.*;
import java.util.zip.*;

final class ModelFiles {
    interface Progress { void update(int percent); boolean cancelled(); }
    static File prepare(Context context, Progress progress) throws Exception {
        File root = new File(context.getFilesDir(),"sensevoice-v2");
        File marker = new File(root,"complete");
        if (marker.isFile() && new File(root,"model.int8.onnx").length()>200000000 && new File(root,"silero_vad.onnx").isFile()) return root;
        if (marker.exists() && !marker.delete()) throw new IOException("无法更新模型状态");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建模型目录");
        String safeRoot = root.getCanonicalPath()+File.separator;
        long written=0; int lastPercent=-1;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("sensevoice-model.zip"))) {
            ZipEntry entry; byte[] buffer=new byte[65536];
            while ((entry=zip.getNextEntry())!=null) {
                if (progress.cancelled()) throw new IOException("已停止");
                File file=new File(root,entry.getName());
                if (!file.getCanonicalPath().startsWith(safeRoot)) throw new IOException("模型路径无效");
                if (entry.isDirectory()) { if (!file.isDirectory() && !file.mkdirs()) throw new IOException("无法创建模型目录"); }
                else {
                    File parent=file.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建模型目录");
                    try (FileOutputStream out=new FileOutputStream(file)) {
                        int n; while ((n=zip.read(buffer))!=-1) {
                            if (progress.cancelled()) throw new IOException("已停止");
                            out.write(buffer,0,n); written+=n;
                            int percent=(int)Math.min(99,written*100/240000000L);
                            if (percent/5!=lastPercent/5) { lastPercent=percent; progress.update(percent); }
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (new File(root,"model.int8.onnx").length()<200000000 || !new File(root,"tokens.txt").isFile() || !new File(root,"silero_vad.onnx").isFile())
            throw new IOException("模型文件不完整");
        if (!marker.createNewFile()) throw new IOException("无法保存模型状态");
        progress.update(100); return root;
    }
}
