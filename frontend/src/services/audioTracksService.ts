import http from './http'

export interface AudioTrack {
  id: string | null
  filename: string | null
  displayName: string
}

export const audioTracksService = {
  async fetchTracks(): Promise<AudioTrack[]> {
    const { data } = await http.get<AudioTrack[]>('/audio/tracks')
    return data
  },
}
