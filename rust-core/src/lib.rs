use jni::objects::{JByteArray, JClass};
use jni::sys::jint;
use jni::JNIEnv;
use std::collections::VecDeque;
use std::panic;

/// 單一光罩傳播網格：寬 x 高 x 長，flat index = ((x * height) + y) * length + z。
/// opacity: 每格擋光程度 0..15（15 = 完全不透光）。
/// light: 傳播中/結果用的光值陣列，呼叫前已經把「已知種子」（光源本身 + 視窗邊界從真實
/// 世界讀進來的既有光值）填好，其餘格先設 0，由 BFS 往外傳播墊高。
struct LightGrid<'a> {
    width: i32,
    height: i32,
    length: i32,
    opacity: &'a [u8],
    light: Vec<u8>,
}

impl<'a> LightGrid<'a> {
    #[inline]
    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        (((x * self.height) + y) * self.length + z) as usize
    }

    #[inline]
    fn in_bounds(&self, x: i32, y: i32, z: i32) -> bool {
        x >= 0 && x < self.width && y >= 0 && y < self.height && z >= 0 && z < self.length
    }

    /// 從目前 light 陣列裡所有非 0 的格子當種子，做標準的 6 方向 BFS flood-fill：
    /// 每跨一格光值依目標格的 opacity（至少遞減 1）遞減，遞減到 <= 0 或不比目前值更亮
    /// 就停止往那個方向繼續傳播——這就是南瓜/vanilla LightEngine 用的同一套核心規則
    /// （只是這裡簡化成一次性從固定種子收斂到穩態，不是逐步增量更新）。
    ///
    /// `is_sky`：天空光額外的特例規則——vanilla 對「正下方」這個方向不是套用一般的
    /// 「至少遞減 1」，而是只要目前這格是滿值 15（代表還在一條沒被擋住的開放天空垂直
    /// 柱裡）且目標格 opacity=0，往下傳播完全不遞減，維持 15，直到真的遇到不透光方塊
    /// 才照 opacity 遞減——這就是「屋頂破洞會有一整條垂直光柱」的效果。方塊光跟天空光
    /// 側向/往上傳播規則完全一樣，只有這個垂直特例不同。
    fn propagate(&mut self, is_sky: bool) {
        let mut queue: VecDeque<(i32, i32, i32)> = VecDeque::new();
        for x in 0..self.width {
            for y in 0..self.height {
                for z in 0..self.length {
                    if self.light[self.index(x, y, z)] > 0 {
                        queue.push_back((x, y, z));
                    }
                }
            }
        }

        const DIRS: [(i32, i32, i32); 6] = [
            (1, 0, 0), (-1, 0, 0),
            (0, 1, 0), (0, -1, 0),
            (0, 0, 1), (0, 0, -1),
        ];

        while let Some((x, y, z)) = queue.pop_front() {
            let cur = self.light[self.index(x, y, z)];
            if cur == 0 {
                continue;
            }
            for (dx, dy, dz) in DIRS {
                let (nx, ny, nz) = (x + dx, y + dy, z + dz);
                if !self.in_bounds(nx, ny, nz) {
                    continue;
                }
                let ni = self.index(nx, ny, nz);
                let is_unobstructed_sky_descent = is_sky && dy == -1 && dx == 0 && dz == 0 && cur == 15;
                let dampening = if is_unobstructed_sky_descent {
                    self.opacity[ni]
                } else {
                    self.opacity[ni].max(1)
                };
                let candidate = cur.saturating_sub(dampening);
                if candidate > self.light[ni] {
                    self.light[ni] = candidate;
                    queue.push_back((nx, ny, nz));
                }
            }
        }
    }
}

fn compute_one(width: i32, height: i32, length: i32, opacity: &[u8], seed: &[i8], is_sky: bool) -> Vec<u8> {
    let size = (width * height * length) as usize;
    let mut light = vec![0u8; size];
    for i in 0..size {
        if seed[i] >= 0 {
            light[i] = seed[i] as u8;
        }
    }
    let mut grid = LightGrid { width, height, length, opacity, light };
    grid.propagate(is_sky);
    grid.light
}

/// 回傳 [blockLight(w*h*l bytes) ++ skyLight(w*h*l bytes)]，任何一步 panic 都會被
/// catch_unwind 接住、回傳 null 讓 Java 端 fallback 回原本的 vanilla checkBlock 路徑
/// （規則：絕不能讓 Rust 端的錯誤直接拖垮呼叫端的 JVM）。
#[no_mangle]
pub extern "system" fn Java_com_toast_worldeditasync_light_RustLightEngine_computeLight<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    width: jint,
    height: jint,
    length: jint,
    opacity: JByteArray<'local>,
    block_light_seed: JByteArray<'local>,
    sky_light_seed: JByteArray<'local>,
) -> jni::sys::jbyteArray {
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| -> Option<Vec<u8>> {
        let size = (width as usize) * (height as usize) * (length as usize);
        let mut opacity_i8 = vec![0i8; size];
        env.get_byte_array_region(&opacity, 0, &mut opacity_i8).ok()?;
        let opacity_buf: Vec<u8> = opacity_i8.iter().map(|&b| b as u8).collect();

        let mut block_seed = vec![0i8; size];
        env.get_byte_array_region(&block_light_seed, 0, &mut block_seed).ok()?;
        let mut sky_seed = vec![0i8; size];
        env.get_byte_array_region(&sky_light_seed, 0, &mut sky_seed).ok()?;

        let (block_light, sky_light) = rayon::join(
            || compute_one(width, height, length, &opacity_buf, &block_seed, false),
            || compute_one(width, height, length, &opacity_buf, &sky_seed, true),
        );

        let mut combined = Vec::with_capacity(size * 2);
        combined.extend_from_slice(&block_light);
        combined.extend_from_slice(&sky_light);
        Some(combined)
    }));

    match result {
        Ok(Some(combined)) => {
            let signed: Vec<i8> = combined.iter().map(|&b| b as i8).collect();
            match env.new_byte_array(signed.len() as i32) {
                Ok(arr) => {
                    if env.set_byte_array_region(&arr, 0, &signed).is_err() {
                        return std::ptr::null_mut();
                    }
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        _ => std::ptr::null_mut(),
    }
}
