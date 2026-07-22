/// Protocol constants
pub const MAGIC_HIGH: u8 = 0xAA;
pub const MAGIC_LOW: u8 = 0xBB;
pub const FRAME_HEADER_SIZE: usize = 5;

/// Message types
pub const MSG_REGISTER: u8 = 0x01;
pub const MSG_NOTIFY: u8 = 0x02;
pub const MSG_ACK: u8 = 0x03;
pub const MSG_ICON_DATA: u8 = 0x04;
pub const MSG_ICON_END: u8 = 0x05;

/// Parsed frame from BLE data
pub struct Frame {
    pub msg_type: u8,
    pub seq: u8,
    pub total_seq: u8,
    pub payload: Vec<u8>,
}

/// Parse a raw BLE frame
pub fn parse_frame(data: &[u8]) -> Option<Frame> {
    if data.len() < FRAME_HEADER_SIZE {
        return None;
    }
    
    if data[0] != MAGIC_HIGH || data[1] != MAGIC_LOW {
        return None;
    }
    
    let msg_type = data[2];
    let seq = data[3];
    let total_seq = data[4];
    
    // 验证 total_seq 不为 0，且 seq 在有效范围内
    if total_seq == 0 || seq >= total_seq {
        return None;
    }
    
    let payload = data[FRAME_HEADER_SIZE..].to_vec();
    
    Some(Frame {
        msg_type,
        seq,
        total_seq,
        payload,
    })
}

/// Build a frame for sending
pub fn build_frame(msg_type: u8, seq: u8, total_seq: u8, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(FRAME_HEADER_SIZE + payload.len());
    frame.push(MAGIC_HIGH);
    frame.push(MAGIC_LOW);
    frame.push(msg_type);
    frame.push(seq);
    frame.push(total_seq);
    frame.extend_from_slice(payload);
    frame
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_valid_frame() {
        let data = vec![0xAA, 0xBB, MSG_NOTIFY, 0, 1, 0x01, 0x02, 0x03];
        let frame = parse_frame(&data).unwrap();
        
        assert_eq!(frame.msg_type, MSG_NOTIFY);
        assert_eq!(frame.seq, 0);
        assert_eq!(frame.total_seq, 1);
        assert_eq!(frame.payload, vec![0x01, 0x02, 0x03]);
    }

    #[test]
    fn test_parse_invalid_magic() {
        let data = vec![0x00, 0x00, MSG_NOTIFY, 0, 1];
        assert!(parse_frame(&data).is_none());
    }

    #[test]
    fn test_parse_too_short() {
        let data = vec![0xAA, 0xBB];
        assert!(parse_frame(&data).is_none());
    }

    #[test]
    fn test_build_frame() {
        let frame = build_frame(MSG_NOTIFY, 0, 1, &[0x01, 0x02]);
        
        assert_eq!(frame[0], 0xAA);
        assert_eq!(frame[1], 0xBB);
        assert_eq!(frame[2], MSG_NOTIFY);
        assert_eq!(frame[3], 0);
        assert_eq!(frame[4], 1);
        assert_eq!(&frame[5..], &[0x01, 0x02]);
    }
}
